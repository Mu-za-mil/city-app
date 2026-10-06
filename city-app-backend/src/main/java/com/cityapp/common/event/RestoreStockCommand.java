package com.cityapp.common.event;

import lombok.*;

import java.time.Instant;
import java.util.List;

/**
 * Saga COMPENSATING COMMAND: restore inventory for a cancelled order.
 *
 * This command is safe to execute more than once. InventoryService only
 * restores a deduction that was recorded as DEDUCTED and atomically marks
 * that deduction RESTORED.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RestoreStockCommand {

    private String sagaId;
    private Long orderId;
    private List<PlaceOrderCommand.OrderItemSpec> items;
    private Instant timestamp;
}