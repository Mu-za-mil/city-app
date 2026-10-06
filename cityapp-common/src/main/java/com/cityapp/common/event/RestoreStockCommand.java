package com.cityapp.common.event;

import lombok.*;

import java.time.Instant;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RestoreStockCommand {
    private String sagaId;
    private Long orderId;
    private Instant timestamp;
}