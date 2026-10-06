package com.cityapp.order.dto;

import com.cityapp.order.entity.OrderType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class PlaceOrderRequest {

    private Long          storeId;
    private OrderType     orderType;
    private String        deliveryAddress;
    private String        notes;
    private String        idempotencyKey;
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
