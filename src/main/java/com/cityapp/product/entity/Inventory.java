package com.cityapp.product.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "inventory")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false, unique = true)
    private Product product;

    @Column(nullable = false)
    @Builder.Default
    private Integer quantity = 0;

    @Column(name = "low_stock_threshold", nullable = false)
    @Builder.Default
    private Integer lowStockThreshold = 10;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Integer version = 0;
    // @Version: optimistic locking.
    // Used for non-critical updates (restocking, threshold changes).
    // The Saga (Phase 10) uses pessimistic locking for order deduction.
    // Two different locking strategies for two different use cases.

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}