package com.cityapp.common.event;

import lombok.*;

import java.time.Instant;

/**
 * Saga REPLY EVENT: inventory compensation completed.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockRestoredEvent {

    private String sagaId;
    private Long orderId;
    private boolean restored;
    private String reason;
    private Instant timestamp;
}