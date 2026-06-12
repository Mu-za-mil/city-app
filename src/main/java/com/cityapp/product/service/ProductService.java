package com.cityapp.product.service;

import com.cityapp.category.repository.CategoryRepository;
import com.cityapp.common.constants.AppConstants;
import com.cityapp.common.exception.AppException;
import com.cityapp.common.response.PageResponse;
import com.cityapp.product.dto.CreateProductRequest;
import com.cityapp.product.dto.ProductResponse;
import com.cityapp.product.entity.Inventory;
import com.cityapp.product.entity.Product;
import com.cityapp.product.mapper.ProductMapper;
import com.cityapp.product.repository.InventoryRepository;
import com.cityapp.product.repository.ProductRepository;
import com.cityapp.product.spec.ProductSpecification;
import com.cityapp.store.entity.Store;
import com.cityapp.store.repository.StoreRepository;
import com.cityapp.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository   productRepository;
    private final InventoryRepository inventoryRepository;
    private final StoreRepository     storeRepository;
    private final CategoryRepository  categoryRepository;
    private final ProductMapper       productMapper;

    // ── Create ────────────────────────────────────────────────────────────────

    @CacheEvict(value = AppConstants.CACHE_PRODUCTS, key = "#result.id")
    @Transactional
    public ProductResponse createProduct(Long storeId, User seller, CreateProductRequest req) {
        // Verify seller owns the store
        Store store = storeRepository
                .findByIdAndOwnerId(storeId, seller.getId())
                .orElseThrow(() -> AppException.notFound("Store not found"));

        Product product = productMapper.toEntity(req);
        product.setStore(store);

        if (req.getCategoryId() != null) {
            product.setCategory(categoryRepository.findById(req.getCategoryId())
                    .orElseThrow(() -> AppException.notFound("Category not found")));
        }

        Product saved = productRepository.save(product);

        // Create inventory record for the product
        Inventory inventory = Inventory.builder()
                .product(saved)
                .quantity(req.getInitialStock())
                .lowStockThreshold(req.getLowStockThreshold())
                .build();
        inventoryRepository.save(inventory);

        log.info("Product created: id={} name='{}' store={}",
                saved.getId(), saved.getName(), store.getId());

        ProductResponse response = productMapper.toResponse(saved);
        response = ProductResponse.builder()
                .id(saved.getId())
                .storeId(saved.getStore().getId())
                .storeName(saved.getStore().getName())
                .name(saved.getName())
                .description(saved.getDescription())
                .price(saved.getPrice())
                .compareAtPrice(saved.getCompareAtPrice())
                .unit(saved.getUnit())
                .active(saved.isActive())
                .imageUrls(saved.getImageUrls())
                .stockQuantity(req.getInitialStock())
                .createdAt(saved.getCreatedAt())
                .build();
        return response;
    }
    // ── Search (JPA Specifications) ───────────────────────────────────────────

    /**
     * Dynamic product search with composable filters.
     *
     * All parameters are optional. Send none → all active products.
     * Send keyword → filter by name/description.
     * Send categoryId + minPrice → filter by both.
     * Any combination works.
     */
    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> searchProducts(
            Long       storeId,
            String     keyword,
            Long       categoryId,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            Pageable   pageable) {

        // Build specification using .and() chaining (all methods now return valid specs)
        Specification<Product> spec = ProductSpecification.isActive()
                .and(ProductSpecification.inStore(storeId))
                .and(ProductSpecification.withKeyword(keyword))
                .and(ProductSpecification.inCategory(categoryId))
                .and(ProductSpecification.priceBetween(minPrice, maxPrice));

        Page<Product> page = productRepository.findAll(spec, pageable);

        // BATCH FETCH inventory to avoid N+1 query
        // WHY: if we call product.getInventory() inside the stream:
        //   20 products → 20 separate SELECT queries for inventory
        //   That's an N+1 query. Slow and wasteful.
        // FIX: fetch all inventory records for all products in ONE query
        List<Long> productIds = page.getContent()
                .stream()
                .map(Product::getId)
                .toList();

        Map<Long, Integer> stockMap = inventoryRepository
                .findByProductIdIn(productIds)
                .stream()
                .collect(Collectors.toMap(
                        inv -> inv.getProduct().getId(),
                        Inventory::getQuantity,
                        (existing, replacement) -> existing // In case of duplicates
                ));

        return PageResponse.from(page.map(product -> {
            ProductResponse resp = productMapper.toResponse(product);
            // Set stockQuantity from the batch-fetched map
            int stockQuantity = stockMap.getOrDefault(product.getId(), 0);

            return ProductResponse.builder()
                    .id(resp.getId())
                    .storeId(resp.getStoreId())
                    .storeName(resp.getStoreName())
                    .name(resp.getName())
                    .description(resp.getDescription())
                    .price(resp.getPrice())
                    .compareAtPrice(resp.getCompareAtPrice())
                    .unit(resp.getUnit())
                    .categoryName(resp.getCategoryName())
                    .active(resp.isActive())
                    .avgRating(resp.getAvgRating())
                    .totalReviews(resp.getTotalReviews())
                    .imageUrls(resp.getImageUrls())
                    .stockQuantity(stockQuantity)
                    .createdAt(resp.getCreatedAt())
                    .build();
        }));
    }
    /**
     * Cache individual product lookups.
     * TTL: 10 minutes (configured in RedisConfig).
     * Evict: when product is updated or deactivated.
     *
     * WHY NOT CACHE PRODUCT SEARCH (searchProducts()):
     *   Search has combinatorial cache keys:
     *   storeId + keyword + categoryId + minPrice + maxPrice + page + size + sort
     *   = thousands of unique combinations.
     *   Cache hit rate would be near 0 (each combination unique).
     *   Cache memory used for keys that are never reused.
     *   RULE: Only cache queries that will be REPEATED with the same parameters.
     *   Product search is never the same twice. Don't cache it.
     *   Product detail (by ID): always the same for the same ID. Cache it.
     */
    @Cacheable(value = AppConstants.CACHE_PRODUCTS, key = "#productId",
            unless = "#result == null")
    @Transactional(readOnly = true)
    public ProductResponse getProductById(Long productId) {
        Product product = productRepository.findByIdAndActiveTrue(productId)
                .orElseThrow(() -> AppException.notFound(
                        "Product not found: " + productId));

        Inventory inv = inventoryRepository.findByProductId(productId)
                .orElse(null);

        return buildProductResponse(product, inv != null ? inv.getQuantity() : 0);
    }

    @CacheEvict(value = AppConstants.CACHE_PRODUCTS, key = "#productId")
    @Transactional
    public ProductResponse updateProduct(Long productId, Long sellerId,
                                         CreateProductRequest req) {
        Product product = productRepository
                .findByIdAndActiveTrue(productId)
                .orElseThrow(() -> AppException.notFound(
                        "Product not found: " + productId));

        if (!product.getStore().getOwner().getId().equals(sellerId)) {
            throw AppException.notFound("Product not found: " + productId);
        }

        if (req.getName() != null)        product.setName(req.getName());
        if (req.getDescription() != null) product.setDescription(req.getDescription());
        if (req.getPrice() != null)       product.setPrice(req.getPrice());
        if (req.getUnit() != null)        product.setUnit(req.getUnit());
        if (req.getImageUrls() != null)   product.setImageUrls(req.getImageUrls());

        return buildProductResponse(
                productRepository.save(product), 0);
    }

    // ── Inventory Management ──────────────────────────────────────────────────

    /**
     * Deduct stock for an order item.
     *
     * @Retryable: if PessimisticLockingFailureException (lock timeout):
     *   wait 50ms and try again. Up to 3 total attempts.
     *   WHY: under high concurrency, multiple threads compete for the same
     *   inventory row's lock. A brief retry handles transient contention.
     *
     * @Recover: if all 3 attempts fail:
     *   return a failure result instead of crashing the entire Saga.
     *   The Saga handler publishes stock.deducted { success: false }.
     *   The order gets cancelled gracefully.
     */
    @Transactional
    @Retryable(
            retryFor = org.springframework.dao.PessimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 50)
    )
    public boolean deductStock(Long productId, int quantity) {
        Inventory inv = inventoryRepository
                .findByProductIdForUpdate(productId)  // SELECT ... FOR UPDATE
                .orElseThrow(() -> AppException.notFound(
                        "Inventory not found for product: " + productId));

        if (inv.getQuantity() < quantity) {
            return false; // Insufficient stock
        }

        inv.setQuantity(inv.getQuantity() - quantity);
        inventoryRepository.save(inv);
        log.debug("Stock deducted: productId={} qty={} remaining={}",
                productId, quantity, inv.getQuantity());
        return true;
    }

    @Recover
    public boolean recoverDeductStock(
            org.springframework.dao.PessimisticLockingFailureException ex,
            Long productId, int quantity) {
        log.error("Stock deduction FAILED after 3 retries: productId={} qty={} error={}",
                productId, quantity, ex.getMessage());
        return false; // Treat as insufficient stock
    }
    // --- Helper Methods

    private ProductResponse buildProductResponse(Product product, int stockQty) {
        return ProductResponse.builder()
                .id(product.getId())
                .storeId(product.getStore().getId())
                .storeName(product.getStore().getName())
                .name(product.getName())
                .description(product.getDescription())
                .sku(product.getSku())
                .price(product.getPrice())
                .compareAtPrice(product.getCompareAtPrice())
                .unit(product.getUnit())
                .categoryName(product.getCategory() != null
                        ? product.getCategory().getName() : null)
                .active(product.isActive())
                .avgRating(product.getAvgRating())
                .totalReviews(product.getTotalReviews())
                .imageUrls(product.getImageUrls())
                .stockQuantity(stockQty)
                .createdAt(product.getCreatedAt())
                .build();
    }

}
