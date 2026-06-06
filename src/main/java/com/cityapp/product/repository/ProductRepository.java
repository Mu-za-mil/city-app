package com.cityapp.product.repository;

import com.cityapp.product.entity.Product;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository
        extends JpaRepository<Product, Long>,
        JpaSpecificationExecutor<Product> {
    // JpaSpecificationExecutor<Product>: adds findAll(Specification, Pageable)
    // This is what enables ProductSpecification to work.

    Optional<Product> findByIdAndStoreIdAndActiveTrue(Long id, Long storeId);
    // For seller: edit their OWN product. Active check: can't edit a deleted product.

    Optional<Product> findByIdAndActiveTrue(Long id);
    // For buyers: view a specific product (must be active).

    // Batch fetch for N+1 prevention (used in OrderService, SearchService)
    List<Product> findByIdInAndActiveTrue(List<Long> ids);
}
