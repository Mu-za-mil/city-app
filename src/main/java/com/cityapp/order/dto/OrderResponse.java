package com.cityapp.order.dto;

import com.cityapp.order.entity.OrderStatus;
import com.cityapp.order.entity.OrderType;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderResponse {

    private Long                  id;
    private Long                  userId;
    private String                buyerName;
    private Long                  storeId;
    private String                storeName;
    private OrderStatus           status;
    private OrderType             orderType;
    private BigDecimal            totalAmount;
    private String                deliveryAddress;
    private List<OrderItemResponse> items;
    private String                cancellationReason;
    private String                notes;
    private String                paymentMethod;
    private String                paymentStatus;
    private Instant               createdAt;
    private Instant               updatedAt;
}