package com.cityapp.store.dto;

import com.cityapp.store.entity.StoreStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;

@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class StoreResponse {

    private Long        id;
    private String      name;
    private String      description;
    private String      address;
    private String      city;
    private String      state;
    private Double      latitude;
    private Double      longitude;
    private String      categoryName;
    private String      categorySlug;
    private String      ownerName;
    private StoreStatus status;
    private boolean     open;
    private LocalTime   openingTime;
    private LocalTime   closingTime;
    private BigDecimal  minOrderAmount;
    private BigDecimal  avgRating;
    private Integer     totalReviews;
    private String      logoUrl;
    private String      bannerUrl;
    private Instant     createdAt;
    private Double      distanceKm;
    // distanceKm: populated by nearby search. null for non-nearby queries.
}

