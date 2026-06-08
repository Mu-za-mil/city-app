package com.cityapp.order.dto;

import lombok.*;
import java.math.BigDecimal;

@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderItemResponse {
    private Long       productId;
    private String     productName;   // from the snapshot
    private BigDecimal unitPrice;     // from the snapshot
    private Integer    quantity;
    private BigDecimal subtotal;
    private String     imageUrl;
}