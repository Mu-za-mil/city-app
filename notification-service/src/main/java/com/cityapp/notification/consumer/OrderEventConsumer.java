package com.cityapp.notification.consumer;

import com.cityapp.common.constants.AppConstants;
import com.cityapp.common.event.OrderCreatedEvent;
import com.cityapp.common.event.OrderStatusChangedEvent;
import com.cityapp.notification.service.NotificationService;
import com.cityapp.order.entity.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes order-related Kafka events and triggers notifications.
 *
 * @KafkaListener:
 *   topics: which Kafka topic(s) to subscribe to.
 *   groupId: the consumer group this listener belongs to.
 *
 * CONSUMER GROUPS — THE KEY KAFKA CONCEPT:
 *   A consumer group is a set of consumers that jointly consume a topic.
 *   Within a group: each partition is consumed by exactly ONE consumer.
 *   Multiple groups: each group gets ALL messages independently.
 *
 *   group "notification-service": gets every order.created message.
 *   group "analytics-service": also gets every order.created message.
 *   → Two services, each gets a full copy. Neither interferes.
 *
 *   group "notification-service" has 3 instances:
 *   3 partitions, 3 consumers → each partition to one consumer.
 *   Horizontal scaling: 3 consumers process 3 partitions in parallel.
 *
 * CONSUMER IDEMPOTENCY:
 *   Kafka guarantees at-least-once delivery.
 *   The SAME message MAY be delivered TWICE.
 *   Why: consumer processes message, commits offset fails, crashes.
 *   On restart: re-reads from last committed offset. Same message again.
 *
 *   Solution: make consumers IDEMPOTENT.
 *   Processing the same message twice = same result as processing once.
 *
 *   Our idempotency strategy: deduplicate by eventId in Redis.
 *   SET "notif:dedup:{eventId}" "1" EX 86400
 *   If key exists: message already processed. Skip.
 *   If key doesn't exist: process, then set the key.
 *
 *   TTL 24 hours: covers any realistic re-delivery window.
 *   After 24 hours: we assume the event won't be re-delivered.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final NotificationService notificationService;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @KafkaListener(
            topics   = AppConstants.TOPIC_ORDER_CREATED,
            groupId  = "notification-service-order-created"
            // Why unique groupId per listener:
            // If two @KafkaListener methods in the same app share a groupId:
            // Kafka assigns each partition to one of them.
            // Some messages go to one method, some to the other.
            // Result: half the notifications are sent, half are dropped.
            // Unique groupId per listener: each listener gets ALL messages.
    )
    public void onOrderCreated(
            @Payload OrderCreatedEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.info("Consuming order.created: orderId={} partition={} offset={}",
                event.getOrderId(), partition, offset);

        // Idempotency check
        if (isAlreadyProcessed(event.getEventId(),
                "order-created-" + event.getOrderId())) {
            log.debug("Duplicate order.created event: eventId={}. Skipping.",
                    event.getEventId());
            return;
        }

        // 1. Send confirmation email to buyer
        notificationService.sendOrderConfirmationEmail(event);

        // 2. Send push notification to buyer
        notificationService.sendOrderCreatedPush(event);

        // 3. Notify seller of new order
        notificationService.sendNewOrderAlertToSeller(event);

        // 4. Create in-app notification record
        notificationService.createInAppNotification(
                event.getUserId(),
                "Order Confirmed",
                "Your order #" + event.getOrderId() + " from " +
                        event.getStoreName() + " has been confirmed.",
                "ORDER",
                event.getOrderId()
        );

        markAsProcessed(event.getEventId(),
                "order-created-" + event.getOrderId());
    }

    @KafkaListener(
            topics  = AppConstants.TOPIC_ORDER_STATUS_CHANGED,
            groupId = "notification-service-order-status"
    )
    public void onOrderStatusChanged(@Payload OrderStatusChangedEvent event) {

        log.info("Consuming order.status.changed: orderId={} {}→{}",
                event.getOrderId(), event.getPreviousStatus(), event.getNewStatus());

        if (isAlreadyProcessed(event.getEventId(),
                "order-status-" + event.getOrderId() + "-" + event.getNewStatus())) {
            return;
        }

        // Notification message varies by new status
        String title   = buildStatusTitle(event.getNewStatus());
        String message = buildStatusMessage(event);

        if (title != null) {
            // Push to buyer
            notificationService.sendStatusChangePush(
                    event.getUserId(), title, message, event.getOrderId());

            // In-app notification
            notificationService.createInAppNotification(
                    event.getUserId(), title, message,
                    "ORDER", event.getOrderId());
        }

        markAsProcessed(event.getEventId(),
                "order-status-" + event.getOrderId() + "-" + event.getNewStatus());
    }

    // ── Idempotency Helpers ───────────────────────────────────────────────────

    private boolean isAlreadyProcessed(String eventId, String fallbackKey) {
        String key = AppConstants.REDIS_NOTIF_DEDUP_PREFIX +
                (eventId != null ? eventId : fallbackKey);
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    private void markAsProcessed(String eventId, String fallbackKey) {
        String key = AppConstants.REDIS_NOTIF_DEDUP_PREFIX +
                (eventId != null ? eventId : fallbackKey);
        redisTemplate.opsForValue().set(key, "1",
                86400, java.util.concurrent.TimeUnit.SECONDS);
    }

    private String buildStatusTitle(OrderStatus status) {
        return switch (status) {
            case PREPARING       -> "Store is preparing your order";
            case READY           -> "Your order is ready!";
            case OUT_FOR_DELIVERY -> "Order is on the way!";
            case DELIVERED       -> "Order delivered successfully";
            case CANCELLED       -> "Order cancelled";
            default              -> null; // no notification for CREATED, CONFIRMED
        };
    }

    private String buildStatusMessage(OrderStatusChangedEvent event) {
        return switch (event.getNewStatus()) {
            case PREPARING        ->
                    "Order #" + event.getOrderId() + " is being prepared.";
            case OUT_FOR_DELIVERY ->
                    "Your delivery partner is on the way.";
            case DELIVERED        ->
                    "Order #" + event.getOrderId() + " was delivered. Enjoy!";
            case CANCELLED        ->
                    "Order #" + event.getOrderId() + " was cancelled. " +
                            (event.getCancellationReason() != null
                                    ? "Reason: " + event.getCancellationReason() : "");
            default -> "Order #" + event.getOrderId() + " status: " + event.getNewStatus();
        };
    }
}
