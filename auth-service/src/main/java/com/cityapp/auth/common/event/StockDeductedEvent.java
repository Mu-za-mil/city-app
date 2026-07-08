package com.cityapp.auth.common.event;

import lombok.*;
import java.time.Instant;

/**
 * Saga REPLY EVENT: "Stock deduction completed (success or failure)."
 *
 * Published by InventoryService after attempting to deduct stock.
 * Consumed by OrderService to advance or cancel the order.
 *
 * WHY success FIELD (not two separate topics):
 *   Option A: publish to "stock.deducted" on success, "stock.deduction.failed" on failure.
 *   Option B: one "stock.deducted" topic with a success boolean (our choice).
 *
 *   Option A: more Kafka topics = more infrastructure to manage.
 *   Option B: one consumer handles both outcomes. Simpler.
 *   OrderService: one @KafkaListener, reads success flag, decides what to do.
 *
 * failureReason: why did the deduction fail?
 *   "Insufficient stock for product 42: available=2 requested=5"
 *   This string becomes the cancellationReason on the order.
 *   Buyer notification: "Your order was cancelled: insufficient stock for Ponni Rice."
 *   Specific. Actionable. Not "something went wrong."
 */
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class StockDeductedEvent {

    private String  sagaId;           // matches the command's sagaId
    private Long    orderId;
    private boolean success;
    private String  failureReason;    // set only if success=false
    private Instant timestamp;
}
