package com.cityapp.common.event;

import lombok.*;
import java.time.Instant;

@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class InventoryLowEvent {

    private String  eventId;
    private Long    productId;
    private String  productName;
    private Long    storeId;
    private Long    sellerId;
    private Integer currentQuantity;
    private Integer threshold;
    private Instant timestamp;
}
