package com.cityapp.config;

import com.cityapp.common.constants.AppConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import java.time.Duration;
import java.util.Map;

/**
 * Kafka Topics, Error Handling, and Dead Letter Topics configuration.
 *
 * TOPIC DESIGN DECISIONS:
 *
 * PARTITIONS (3 per topic):
 *   Each partition can be consumed by ONE consumer in a consumer group.
 *   partitions=1: only 1 consumer can process messages at a time.
 *   partitions=3: up to 3 consumers can process in parallel.
 *   Rule of thumb: partitions = expected peak consumers × 2.
 *   For development: 3 is sufficient. Scale up when needed.
 *   WHY NOT 100: more partitions = more overhead.
 *                Kafka tracks metadata for each partition.
 *                Start small. Scale when needed.
 *
 * REPLICATION FACTOR (1 in dev, 3 in prod):
 *   In development: single broker, replication=1 (no replicas needed).
 *   In production: 3 brokers, replication=3.
 *   Rule: replication factor ≤ number of brokers.
 *   With replication=3: if one broker dies, 2 others have the data.
 *   Topic survives 2 broker failures.
 *
 * RETENTION (7 days default):
 *   Messages stored in Kafka for 7 days even after consumption.
 *   Use case: replay historical events if a consumer was down.
 *   "Notification service was down for 2 hours. Replay all missed events."
 *   Without retention: missed events are gone forever.
 *
 * DEAD LETTER TOPICS (DLT):
 *   If a consumer fails to process a message:
 *   Without DLT: Kafka keeps redelivering forever. Blocks the partition.
 *   With DLT: after retries exhausted, message sent to "topic.DLT".
 *   Operations team monitors DLT. Investigates. Replays when fixed.
 *   Normal processing continues unblocked.
 *
 *   DLT naming convention: "{original-topic}.DLT"
 *   order.created → order.created.DLT
 */
@Slf4j
@Configuration
public class KafkaConfig {

    // ── Topic Definitions ─────────────────────────────────────────────────────

    @Bean
    public NewTopic userRegisteredTopic() {
        return TopicBuilder.name(AppConstants.TOPIC_USER_REGISTERED)
                .partitions(3)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(Duration.ofDays(7).toMillis()))
                .build();
    }

    @Bean
    public NewTopic orderCreatedTopic() {
        return TopicBuilder.name(AppConstants.TOPIC_ORDER_CREATED)
                .partitions(3)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(Duration.ofDays(7).toMillis()))
                .build();
    }

    @Bean
    public NewTopic orderStatusChangedTopic() {
        return TopicBuilder.name(AppConstants.TOPIC_ORDER_STATUS_CHANGED)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic deductStockTopic() {
        return TopicBuilder.name(AppConstants.TOPIC_DEDUCT_STOCK)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic stockDeductedTopic() {
        return TopicBuilder.name(AppConstants.TOPIC_STOCK_DEDUCTED)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic inventoryLowTopic() {
        return TopicBuilder.name(AppConstants.TOPIC_INVENTORY_LOW)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic deliveryAssignedTopic() {
        return TopicBuilder.name(AppConstants.TOPIC_DELIVERY_ASSIGNED)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic storeAnnouncementTopic() {
        return TopicBuilder.name(AppConstants.TOPIC_STORE_ANNOUNCEMENT)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic reviewPostedTopic() {
        return TopicBuilder.name(AppConstants.TOPIC_REVIEW_POSTED)
                .partitions(3)
                .replicas(1)
                .build();
    }

    // ── Dead Letter Topics ────────────────────────────────────────────────────

    @Bean
    public NewTopic orderCreatedDlt() {
        return TopicBuilder.name(AppConstants.TOPIC_ORDER_CREATED + ".DLT")
                .partitions(1)
                // DLTs don't need many partitions:
                // messages arriving here are exceptional (failures).
                // Not high-throughput.
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(Duration.ofDays(30).toMillis()))
                // DLT retention: 30 days.
                // Operations team needs time to investigate and replay.
                .build();
    }

    // Add similar DLT for each business-critical topic:
    @Bean public NewTopic inventoryLowDlt() {
        return TopicBuilder.name(AppConstants.TOPIC_INVENTORY_LOW + ".DLT")
                .partitions(1).replicas(1).build();
    }

    @Bean public NewTopic deliveryAssignedDlt() {
        return TopicBuilder.name(AppConstants.TOPIC_DELIVERY_ASSIGNED + ".DLT")
                .partitions(1).replicas(1).build();
    }

    // ── Error Handler (Retry + Dead Letter) ──────────────────────────────────

    /**
     * Global Kafka consumer error handler.
     * Applies to ALL @KafkaListener methods in the application.
     *
     * RETRY STRATEGY: Exponential Backoff
     *   Attempt 1: immediate
     *   Attempt 2: wait 1 second
     *   Attempt 3: wait 2 seconds
     *   Attempt 4: wait 4 seconds
     *   Attempt 5: wait 8 seconds (maxInterval cap)
     *   Total wait: ~15 seconds before giving up.
     *
     *   WHY EXPONENTIAL BACKOFF:
     *   Transient failure (DB blip, network hiccup): usually recovers in < 1s.
     *   First retry: catches transient failures.
     *   Exponential: if still failing, something is truly wrong.
     *   Don't hammer a struggling service with constant retries.
     *   Each retry doubles the wait. Pressure reduces. System has time to recover.
     *
     * AFTER MAX RETRIES: DeadLetterPublishingRecoverer
     *   Sends the failed message to "{topic}.DLT".
     *   Processing of other messages CONTINUES (partition not blocked).
     *   Operations team: monitors DLT, investigates, replays when fixed.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, Object> kafkaTemplate) {

        // After max retries: send to DLT
        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(kafkaTemplate);

        // Exponential backoff: 1s, 2s, 4s, 8s, 8s (max 5 attempts)
        ExponentialBackOffWithMaxRetries backOff =
                new ExponentialBackOffWithMaxRetries(5);
        backOff.setInitialInterval(1_000);    // 1 second initial
        backOff.setMultiplier(2.0);           // double each retry
        backOff.setMaxInterval(8_000);        // cap at 8 seconds

        DefaultErrorHandler errorHandler =
                new DefaultErrorHandler(recoverer, backOff);

        // Don't retry non-recoverable exceptions:
        // Data deserialization errors: retrying with same bad data won't help.
        errorHandler.addNotRetryableExceptions(
                org.springframework.kafka.support.serializer.DeserializationException.class,
                com.fasterxml.jackson.core.JsonParseException.class
        );

        return errorHandler;
    }
}
