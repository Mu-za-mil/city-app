package com.cityapp.order.dto;

import com.cityapp.order.entity.OrderType;
import lombok.*;

import java.util.List;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class PlaceOrderRequest {

    private Long          storeId;
    private OrderType     orderType;
    private String        deliveryAddress;
    private String        notes;
    private String        idempotencyKey;   // client-generated UUID
    private List<OrderItemRequest> items;
}
