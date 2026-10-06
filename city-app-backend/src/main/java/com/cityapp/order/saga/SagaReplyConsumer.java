package com.cityapp.order.saga;

import com.cityapp.common.constants.AppConstants;
import com.cityapp.common.event.OrderCreatedEvent;
import com.cityapp.common.event.OrderStatusChangedEvent;
import com.cityapp.common.event.StockDeductedEvent;
import com.cityapp.order.entity.Order;
import com.cityapp.outbox.service.OutboxService;
import com.cityapp.order.entity.OrderStatus;
import com.cityapp.order.repository.OrderRepository;
import com.cityapp.common.event.EventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Consumes StockDeductedEvent and advances or cancels the order.
 *
 * This is the "order confirmation step" of the Saga.
 *
 * SUCCESS PATH:
 *   StockDeductedEvent { success: true }
 *   → Order: CREATED → CONFIRMED
 *   → Publish: order.created (triggers notifications)
 *
 * FAILURE PATH (COMPENSATING TRANSACTION):
 *   StockDeductedEvent { success: false, reason: "Insufficient stock" }
 *   → Order: CREATED → CANCELLED
 *   → Set cancellationReason = failure reason
 *   → Publish: order.status.changed (triggers buyer notification)
 *
 *   WHY "COMPENSATING TRANSACTION":
 *   The order was CREATED (a DB write happened).
 *   The stock deduction FAILED.
 *   We cannot un-create the order (the DB write happened).
 *   Instead: we COMPENSATE by setting the order to CANCELLED.
 *   The cancellation is the compensating transaction.
 *   This is a fundamental pattern in distributed systems:
 *   you cannot rollback across service boundaries.
 *   You compensate with a forward action that reverses the effect.
 *
 * IDEMPOTENCY:
 *   stock.deducted may be delivered twice (Kafka at-least-once).
 *   Without idempotency: order might transition CONFIRMED → CONFIRMED → error.
 *   With idempotency: second delivery → order is already CONFIRMED → skip.
 *
 *   Strategy: check order.status before updating.
 *   If status is not CREATED: already processed. Return.
 *   Simple and correct. No Redis needed here (order status IS the state).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaReplyConsumer {

    private final OrderRepository orderRepository;
    private final OutboxService outboxService;

    @KafkaListener(
            topics  = AppConstants.TOPIC_STOCK_DEDUCTED,
            groupId = "order-service-stock-deducted"
    )
    @Transactional
    public void onStockDeducted(@Payload StockDeductedEvent event) {

        log.info("Saga reply received: sagaId={} orderId={} success={}",
                event.getSagaId(), event.getOrderId(), event.isSuccess());

        // Load the order
        Order order = orderRepository.findById(event.getOrderId())
                .orElse(null);

        if (order == null) {
            log.warn("Saga reply for unknown orderId={}. Ignoring.",
                    event.getOrderId());
            return;
        }

        // ── Idempotency: only process if order is still CREATED ──────────────

        if (order.getStatus() != OrderStatus.CREATED) {
            log.debug("Order {} is already in status {}. Saga reply ignored.",
                    event.getOrderId(), order.getStatus());
            return;
            /*
             * WHY THIS IS THE CORRECT IDEMPOTENCY CHECK:
             *
             * First delivery: order is CREATED → process → advance to CONFIRMED.
             * Second delivery (duplicate): order is CONFIRMED → skip.
             *
             * This is STATUS-BASED IDEMPOTENCY.
             * The order's status IS the idempotency state.
             * No separate Redis key needed.
             *
             * EDGE CASE: what if the SagaTimeoutHandler already cancelled this order
             * (order stuck in CREATED for 2+ minutes) and THEN stock.deducted arrives?
             * Order is CANCELLED. This check: skip.
             * The stock WAS deducted (inventory service did it).
             * But the order is cancelled.
             * PROBLEM: inventory is deducted but order is cancelled. Stock is "lost."
             *
             * FIX: publish a restore.stock command when order is cancelled after deduction.
             * The inventory service restores the quantity.
             * This is the complete compensating transaction.
             * For Phase 10: document this gap. Address in production hardening.
             */
        }

        if (event.isSuccess()) {
            // ── Happy Path: Advance to CONFIRMED ─────────────────────────────
            handleSuccess(order, event);
        } else {
            // ── Failure Path: Compensating Transaction (Cancel) ───────────────
            handleFailure(order, event);
        }
    }

    private void handleSuccess(Order order, StockDeductedEvent event) {
        order.setStatus(OrderStatus.CONFIRMED);
        orderRepository.save(order);

        log.info("Order CONFIRMED: orderId={} sagaId={}",
                order.getId(), event.getSagaId());

        // Now publish order.created (was deferred until confirmation)
        // This triggers buyer email, seller notification, etc.
        List<OrderCreatedEvent.OrderItemInfo> itemInfos = order.getItems()
                .stream()
                .map(item -> OrderCreatedEvent.OrderItemInfo.builder()
                        .productId(item.getProduct().getId())
                        .productName(item.getProductName())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .build())
                .toList();

        outboxService.enqueue(
                AppConstants.TOPIC_ORDER_CREATED,
                String.valueOf(order.getId()),
                OrderCreatedEvent.builder()
                        .eventId(EventPublisher.generateEventId())
                        .orderId(order.getId())
                        .userId(order.getUser().getId())
                        .sellerId(order.getStore().getOwner().getId())
                        .storeId(order.getStore().getId())
                        .storeName(order.getStore().getName())
                        .orderType(order.getOrderType())
                        .totalAmount(order.getTotalAmount())
                        .deliveryAddress(order.getDeliveryAddress())
                        .items(itemInfos)
                        .timestamp(Instant.now())
                        .build());
    }

    private void handleFailure(Order order, StockDeductedEvent event) {
        order.setStatus(OrderStatus.CANCELLED);
        order.setCancellationReason(
                "Order automatically cancelled: " + event.getFailureReason());
        orderRepository.save(order);

        log.warn("Order CANCELLED (insufficient stock): orderId={} reason={}",
                order.getId(), event.getFailureReason());

        // Notify buyer: your order was cancelled
        outboxService.enqueue(
                AppConstants.TOPIC_ORDER_STATUS_CHANGED,
                String.valueOf(order.getId()),
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
