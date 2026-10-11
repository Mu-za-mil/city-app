package com.cityapp.order.dto;

import com.cityapp.order.entity.OrderType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class PlaceOrderRequest {

    @NotNull(message = "Store ID is required")
    @Positive(message = "Store ID must be positive")
    private Long storeId;

    @NotNull(message = "Order type is required")
    private OrderType orderType;

    @Size(max = 2000, message = "Delivery address must be at most 2000 characters")
    private String deliveryAddress;

    @Size(max = 1000, message = "Notes must be at most 1000 characters")
    private String notes;

    @Size(max = 128, message = "Idempotency key must be at most 128 characters")
    private String idempotencyKey;

    @NotEmpty(message = "At least one order item is required")
    @Valid
    private List<OrderItemRequest> items;

    /**
     * Server-only price snapshots.
     *
     * This field is deliberately excluded from JSON so a client cannot
     * supply or override trusted pricing through the HTTP request.
     *
     * Cart checkout populates this from the server-owned Redis cart snapshot.
     * Direct order construction without a trusted snapshot falls back to the
     * authoritative Product.price in OrderService.
     */
    @JsonIgnore
    @Builder.Default
    private Map<Long, BigDecimal> trustedUnitPrices = new HashMap<>();
}
