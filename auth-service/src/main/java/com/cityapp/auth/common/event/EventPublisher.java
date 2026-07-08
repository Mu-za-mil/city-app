package com.cityapp.auth.common.event;

import com.cityapp.auth.common.constants.AppConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Central publisher for all Kafka events.
 *
 * WHY A CENTRAL PUBLISHER (not direct kafkaTemplate.send() everywhere):
 *
 *   Without it: services call kafkaTemplate.send() directly.
 *   topic name: string literal scattered across 10 service classes.
 *   Error handling: each service handles Kafka failures differently.
 *   Logging: some services log, others don't.
 *   Testing: mock kafkaTemplate in every test.
 *
 *   With EventPublisher:
 *   One place for all publishing logic.
 *   Consistent error handling: Kafka failures are logged but NEVER crash the caller.
 *   Consistent logging: every published event is logged with topic + key + eventId.
 *   Testing: mock EventPublisher in tests. Much simpler.
 *
 * FIRE-AND-FORGET vs AWAIT CONFIRMATION:
 *   kafkaTemplate.send() returns a CompletableFuture.
 *   Two options:
 *   A. Fire and forget: publish and return without waiting for Kafka ACK.
 *      Pro: fastest. Caller not blocked by Kafka latency.
 *      Con: if Kafka is down, the event is silently lost.
 *
 *   B. Await confirmation: .get() on the CompletableFuture.
 *      Pro: guaranteed delivery before returning to caller.
 *      Con: adds 5-20ms Kafka round-trip latency to every business operation.
 *
 *   OUR APPROACH: B for critical events (order.created), A for non-critical.
 *   But also: Transactional Outbox (Phase 17) solves this completely.
 *   The outbox writes event to DB atomically with the business data.
 *   OutboxPublisher delivers to Kafka with retries. Guaranteed delivery.
 *   For now: best-effort publishing with error logging.
 *
 * MESSAGE KEY (partition key):
 *   Kafka uses the key to determine which partition a message goes to.
 *   Same key → same partition → ORDERED delivery within that key.
 *
 *   order.created key = orderId (String)
 *   "All events for order 42 go to the same partition, in order."
 *   OrderCreated → StockDeducted → OrderConfirmed: all for order 42.
 *   All in the same partition. Consumed in order. Correct state machine.
 *
 *   If we used a random key: events for the same order could go to different partitions.
 *   Consumed out of order: OrderConfirmed before OrderCreated.
 *   Consumer: "I don't know this order yet." Error.
 *
 *   Partition key strategy: use the entity ID that the event is about.
 *   order events → orderId as key
 *   user events → userId as key
 *   store events → storeId as key
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    // ── User Events ───────────────────────────────────────────────────────────

    public void publishUserRegistered(UserRegisteredEvent event) {
        publish(AppConstants.TOPIC_USER_REGISTERED,
                String.valueOf(event.getUserId()), event);
    }

    // ── Order Events ──────────────────────────────────────────────────────────

    public void publishOrderCreated(OrderCreatedEvent event) {
        publish(AppConstants.TOPIC_ORDER_CREATED,
                String.valueOf(event.getOrderId()), event);
    }

    public void publishOrderStatusChanged(OrderStatusChangedEvent event) {
        publish(AppConstants.TOPIC_ORDER_STATUS_CHANGED,
                String.valueOf(event.getOrderId()), event);
    }

    // ── Inventory Events ──────────────────────────────────────────────────────

    public void publishInventoryLow(InventoryLowEvent event) {
        publish(AppConstants.TOPIC_INVENTORY_LOW,
                String.valueOf(event.getProductId()), event);
    }

    // ── Delivery Events ───────────────────────────────────────────────────────

    public void publishDeliveryAssigned(DeliveryAssignedEvent event) {
        publish(AppConstants.TOPIC_DELIVERY_ASSIGNED,
                String.valueOf(event.getOrderId()), event);
    }

    // ── Review Events ─────────────────────────────────────────────────────────

    public void publishReviewPosted(ReviewPostedEvent event) {
        publish(AppConstants.TOPIC_REVIEW_POSTED,
                String.valueOf(event.getTargetId()), event);
    }


    // ── Core Publish Method ───────────────────────────────────────────────────

    /**
     * Publishes an event to Kafka.
     *
     * FAILURE HANDLING:
     *   If Kafka is unavailable: log error but DO NOT throw.
     *   The business operation (order placement) already succeeded.
     *   The Kafka event is an eventual side effect.
     *   Throwing here would roll back the order transaction. Wrong.
     *
     *   Phase 17 (Transactional Outbox) solves this properly:
     *   events are written to DB atomically with business data.
     *   If Kafka is down: events wait in DB, delivered when Kafka recovers.
     *   For now: best-effort with error logging.
     */
    private void publish(String topic, String key, Object event) {
        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(topic, key, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish event: topic={} key={} eventType={} error={}",
                        topic, key, event.getClass().getSimpleName(), ex.getMessage());
                // NOT rethrowing: business operation succeeded. Event loss is
                // acceptable here. Outbox pattern (Phase 17) provides at-least-once.
            } else {
                log.debug("Event published: topic={} key={} partition={} offset={}",
                        topic, key,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }

    /**
     * Generates a unique event ID for idempotency.
     * Consumers use this to detect and skip duplicate deliveries.
     */
    public static String generateEventId() {
        return UUID.randomUUID().toString();
    }
}