package com.cityapp.product.service;

import com.cityapp.category.repository.CategoryRepository;
import com.cityapp.common.exception.AppException;
import com.cityapp.product.dto.CreateProductRequest;
import com.cityapp.product.dto.ProductResponse;
import com.cityapp.product.entity.Inventory;
import com.cityapp.product.entity.Product;
import com.cityapp.product.mapper.ProductMapper;
import com.cityapp.product.repository.InventoryRepository;
import com.cityapp.product.repository.ProductRepository;
import com.cityapp.store.entity.Store;
import com.cityapp.store.repository.StoreRepository;
import com.cityapp.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}
