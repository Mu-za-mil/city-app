package com.cityapp.product.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Getter @Setter
public class CreateProductRequest {

    @NotBlank(message = "Product name is required")
    @Size(max = 300, message = "Name must be at most 300 characters")
    private String name;

    @Size(max = 2000, message = "Description must be at most 2000 characters")
    private String description;

    private String sku;

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.01", message = "Price must be greater than 0")
    private BigDecimal price;

    private BigDecimal compareAtPrice;
    private String     unit;
    private Long       categoryId;
    private String[]   imageUrls;

    @NotNull(message = "Initial stock quantity is required")
    @Min(value = 0, message = "Stock quantity cannot be negative")
    private Integer initialStock;

    @Min(value = 1, message = "Low stock threshold must be at least 1")
    private Integer lowStockThreshold = 10;
}
