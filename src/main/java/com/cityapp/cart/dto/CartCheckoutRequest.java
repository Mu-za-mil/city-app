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
}
