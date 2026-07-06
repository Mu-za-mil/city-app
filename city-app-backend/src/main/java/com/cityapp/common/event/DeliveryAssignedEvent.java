package com.cityapp.common.event;

import lombok.*;
import java.time.Instant;

@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class DeliveryAssignedEvent {

    private String  eventId;
    private Long    orderId;
    private Long    partnerId;
    private String  partnerName;
    private String  partnerPhone;
    private Long    storeId;
    private Long    buyerId;
    private Instant assignedAt;
}