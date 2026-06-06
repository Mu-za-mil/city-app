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

        Specification<Product> spec = Specification
                .allOf(ProductSpecification.isActive())
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
                .stream().map(Product::getId).toList();

        Map<Long, Integer> stockMap = inventoryRepository
                .findByProductIdIn(productIds)
                .stream()
                .collect(Collectors.toMap(
                        inv -> inv.getProduct().getId(),
                        Inventory::getQuantity));

        return PageResponse.from(page.map(product -> {
            ProductResponse resp = productMapper.toResponse(product);
            // Set stockQuantity from the batch-fetched map
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
                    .stockQuantity(stockMap.getOrDefault(resp.getId(), 0))
                    .createdAt(resp.getCreatedAt())
                    .build();
        }));
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
}
