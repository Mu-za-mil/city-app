package com.cityapp.cart.dto;

import com.cityapp.order.entity.OrderType;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class CartCheckoutRequest {

    @NotNull(message = "Store ID is required")
    private Long storeId;

    @NotNull(message = "Order type is required")
    private OrderType orderType;

    // Required if orderType = DELIVERY
    private String deliveryAddress;
    private Long   savedAddressId;

    private String notes;

    // ── Idempotency ───────────────────────────────────────────────────────────
    /**
     * Client-generated UUID for idempotent checkout.
     * If provided, OrderService ensures that retrying with the same key
     * returns the same order (no duplicate orders created).
     *
     * Example: "checkout-session-uuid-12345"
     * First checkout: creates order, stores idempotencyKey.
     * Retry with same key: returns existing order (no new order created).
     */
    private String idempotencyKey;
}
