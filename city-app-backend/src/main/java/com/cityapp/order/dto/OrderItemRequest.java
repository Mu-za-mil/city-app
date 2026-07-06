package com.cityapp.order.dto;

import lombok.*;
import java.math.BigDecimal;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderItemRequest {

    private Long       productId;
    private Integer    quantity;
    private BigDecimal unitPrice;   // snapshot price from CartItem
}