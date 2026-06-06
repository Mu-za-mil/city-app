package com.cityapp.product.entity;

import com.cityapp.category.entity.Category;
import com.cityapp.store.entity.Store;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "products")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(nullable = false)
    private String name;

    @Column(length = 2000)
    private String description;

    private String sku;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;
    // NEVER use double/float for price. NUMERIC(12,2) → BigDecimal in Java.

    @Column(name = "compare_at_price", precision = 12, scale = 2)
    private BigDecimal compareAtPrice;
    // "Was ₹200, Now ₹150" — the strikethrough price shown on product card.

    @Builder.Default
    private String unit = "piece";

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
    // WHY SOFT DELETE:
    // If we hard-delete a product: order_items.product_id FK breaks.
    // DB rejects DELETE: "violates foreign key constraint".
    // Solution: set active=false. Product invisible to buyers. History preserved.

    @Column(name = "avg_rating", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal avgRating = BigDecimal.ZERO;

    @Column(name = "total_reviews")
    @Builder.Default
    private Integer totalReviews = 0;

    @Column(name = "image_urls", columnDefinition = "TEXT[]")
    private String[] imageUrls;
    // PostgreSQL array: multiple image URLs in one column.
    // Alternative: product_images table (normalised).
    // Array is simpler for ≤5 images. JOIN not needed.

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
