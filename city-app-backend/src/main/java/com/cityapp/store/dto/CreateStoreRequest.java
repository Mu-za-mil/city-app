package com.cityapp.store.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalTime;

@Getter @Setter
public class CreateStoreRequest {

    @NotBlank(message = "Store name is required")
    @Size(max = 200, message = "Store name must be at most 200 characters")
    private String name;

    @Size(max = 2000, message = "Description must be at most 2000 characters")
    private String description;

    private String address;
    private String city;
    private String state;
    private String pincode;

    @DecimalMin(value = "-90.0",  message = "Latitude must be >= -90")
    @DecimalMax(value = "90.0",   message = "Latitude must be <= 90")
    private Double latitude;

    @DecimalMin(value = "-180.0", message = "Longitude must be >= -180")
    @DecimalMax(value = "180.0",  message = "Longitude must be <= 180")
    private Double longitude;

    private Long   categoryId;
    private String logoUrl;
    private String bannerUrl;
    private LocalTime openingTime;
    private LocalTime closingTime;

    @DecimalMin(value = "0.0", message = "Minimum order amount cannot be negative")
    private BigDecimal minOrderAmount;
}
