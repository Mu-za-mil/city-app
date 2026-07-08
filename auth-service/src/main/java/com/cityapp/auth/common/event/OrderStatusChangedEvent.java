package com.cityapp.auth.common.event;

import com.cityapp.auth.entity.OrderStatus;
import lombok.*;
import java.time.Instant;

@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderStatusChangedEvent {

    private String      eventId;
    private Long        orderId;
    private Long        userId;       // buyer (for push notification)
    private Long        sellerId;
    private OrderStatus previousStatus;
    private OrderStatus newStatus;
    private String      cancellationReason;
    private Instant     timestamp;
}
