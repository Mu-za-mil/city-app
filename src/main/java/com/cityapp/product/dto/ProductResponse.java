package com.cityapp.product.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class ProductResponse {
    private Long       id;
    private Long       storeId;
    private String     storeName;
    private String     name;
    private String     description;
    private String     sku;
    private BigDecimal price;
    private BigDecimal compareAtPrice;
    private String     unit;
    private String     categoryName;
    private boolean    active;
    private BigDecimal avgRating;
    private Integer    totalReviews;
    private String[]   imageUrls;
    private Integer    stockQuantity;
    // stockQuantity: joined from inventory. Not on Product entity.
    // Set manually in service after mapping.
    private Instant    createdAt;
}
