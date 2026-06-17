package com.cityapp.order.service;

import com.cityapp.common.event.EventPublisher;
import com.cityapp.common.event.OrderStatusChangedEvent;
import com.cityapp.order.entity.Order;
import com.cityapp.order.entity.OrderStatus;
import com.cityapp.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Cancels orders stuck in CREATED status for too long.
 *
 * WHY THIS IS NEEDED:
 *   Normal flow: CREATED → (inventory deducted) → CONFIRMED
 *   The transition from CREATED to CONFIRMED happens within the
 *   placeOrder() transaction (Phase 7 local transaction).
 *   Or via Kafka Saga (Phase 10 distributed transaction).
 *
 *   FAILURE SCENARIO:
 *   Kafka is temporarily unavailable (Phase 10 Saga path).
 *   Order is CREATED. deduct.stock event cannot be published.
 *   Inventory is NOT deducted.
 *   Order is stuck in CREATED forever.
 *
 *   Without this handler:
 *   Order appears in buyer's history as "pending" forever.
 *   Seller's dashboard shows ghost orders.
 *   Inventory appears available (it was never deducted).
 *
 *   With this handler:
 *   Any CREATED order older than TIMEOUT_MINUTES:
 *   → status set to CANCELLED
 *   → buyer sees clear failure: "order could not be processed"
 *   → buyer can retry
 *   → no ghost orders in seller's dashboard
 *
 * TIMEOUT: 2 minutes.
 * Normal order processing (inventory deduction via DB or Kafka): < 500ms.
 * If still CREATED after 2 minutes: something definitely went wrong.
 * 2 minutes is generous. 30 seconds would also work.
 *
 * RUNS EVERY 60 SECONDS.
 * Small window of up to 60 seconds where a stuck order isn't cancelled yet.
 * Acceptable trade-off: cancelling every 1 second is excessive DB load.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaTimeoutHandler {

    private final OrderRepository orderRepository;
    private final EventPublisher eventPublisher;

    private static final long TIMEOUT_MINUTES = 2L;

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void cancelTimedOutOrders() {

        Instant cutoff = Instant.now().minus(TIMEOUT_MINUTES, ChronoUnit.MINUTES);
        List<Order> stuckOrders = orderRepository.findCreatedOrdersOlderThan(cutoff);

        if (stuckOrders.isEmpty()) return;

        log.warn("SagaTimeoutHandler: {} orders stuck in CREATED status > {} minutes",
                stuckOrders.size(), TIMEOUT_MINUTES);

        for (Order order : stuckOrders) {
            /*
             * WHY WE CANCEL NOT RETRY:
             *   A stuck CREATED order means either:
             *   A. deduct.stock was published but inventory service is down.
             *      Retrying: the order will never advance while service is down.
             *      Cancel now: buyer is notified. Buyer can retry when service recovers.
             *      Inventory service will consume the deduct.stock when it recovers.
             *      SagaReplyConsumer: order is already CANCELLED → ignores the reply.
             *      Potential issue: stock was deducted, order was cancelled.
             *      TODO: publish restore.stock command here.
             *
             *   B. deduct.stock was never published (Kafka was down at publish time).
             *      Inventory is NOT deducted.
             *      Cancel safely. Inventory is consistent.
             *
             *   C. deduct.stock consumer crashed after deducting, before publishing reply.
             *      Consumer recovers, re-processes: idempotency check (Redis key exists) → skips.
             *      No second deduction. But also no reply published.
             *      Saga is stuck. Cancel. Stock restoration needed.
             *
             *   For all three cases: cancellation is the safe choice.
             *   Stock restoration is a future improvement (Phase 17).
             */
            order.setStatus(OrderStatus.CANCELLED);
            order.setCancellationReason(
                    "Order automatically cancelled: inventory deduction did not complete " +
                            "within " + TIMEOUT_MINUTES + " minutes. Please try again.");
            orderRepository.save(order);

            log.info("Order auto-cancelled by SagaTimeoutHandler: id={} buyer={}",
                    order.getId(), order.getUser().getEmail());

            // Publish cancellation event so buyer is notified
            eventPublisher.publishOrderStatusChanged(
                    OrderStatusChangedEvent.builder()
                            .eventId(EventPublisher.generateEventId())
                            .orderId(order.getId())
                            .userId(order.getUser().getId())
                            .sellerId(order.getStore().getOwner().getId())
                            .previousStatus(OrderStatus.CREATED)
                            .newStatus(OrderStatus.CANCELLED)
                            .cancellationReason(order.getCancellationReason())
                            .timestamp(Instant.now())
                            .build());
        }
    }

}
