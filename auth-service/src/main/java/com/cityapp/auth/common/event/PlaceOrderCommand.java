package com.cityapp.auth.common.event;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Saga COMMAND: "Please deduct stock for these items."
 *
 * WHY "COMMAND" NOT "EVENT":
 *   Events: "something happened" (past tense, immutable fact)
 *   Commands: "please do this thing" (imperative, may be rejected)
 *
 *   This is a REQUEST to the inventory service.
 *   The inventory service may REJECT it (insufficient stock).
 *   A command that gets rejected is not a fact.
 *   Naming matters: PlaceOrderCommand tells future engineers
 *   "this is an instruction, not a notification."
 *
 * SAGA CORRELATION:
 *   sagaId: unique ID for this saga execution.
 *   Links PlaceOrderCommand → StockDeductedEvent.
 *   OrderService: "I sent command with sagaId=abc. When I receive
 *   stock.deducted with sagaId=abc, it's for MY order."
 *
 * WHY NOT USE orderId AS CORRELATION:
 *   orderId would work. sagaId is more explicit.
 *   In complex sagas with retries: same orderId, different sagaId.
 *   sagaId = "this specific attempt at this saga step."
 */
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class PlaceOrderCommand {

    private String     sagaId;         // correlation ID for this saga
    private Long       orderId;        // which order this is for
    private Long       userId;
    private Long       storeId;
    private List<OrderItemSpec> items;
    private Instant    timestamp;

    @Getter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OrderItemSpec {
        private Long    productId;
        private Integer quantity;
        private BigDecimal unitPrice;   // for audit: the price that was charged
    }
}