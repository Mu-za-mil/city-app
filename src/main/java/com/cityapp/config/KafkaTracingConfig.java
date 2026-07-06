package com.cityapp.config;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * Configures distributed tracing to propagate across Kafka messages.
 *
 * WHY KAFKA TRACE PROPAGATION:
 *   Without it: an HTTP request creates a trace.
 *   The request publishes to Kafka. A consumer processes the message.
 *   The consumer's processing: a SEPARATE, UNRELATED trace.
 *   You cannot connect "order placement" (HTTP trace) to
 *   "notification sent" (Kafka consumer trace).
 *
 *   With propagation:
 *   HTTP request: traceId = "abc123"
 *   Kafka message: includes traceId = "abc123" in headers
 *   Kafka consumer: continues the trace with traceId = "abc123"
 *
 *   In Zipkin: one waterfall showing:
 *   HTTP placeOrder (200ms) → Kafka publish (5ms) → consumer process (120ms)
 *   → email send (200ms) → push notification (150ms)
 *   Total end-to-end: 675ms for one checkout.
 *
 * B3 PROPAGATION:
 *   B3 is Zipkin's trace propagation format.
 *   Header: X-B3-TraceId, X-B3-SpanId, X-B3-Sampled
 *   Spring Kafka + Micrometer: automatically injects B3 headers into Kafka messages.
 *   Consumers: automatically extract B3 headers from Kafka messages.
 *   No code changes needed in producers or consumers.
 *   Just this configuration bean.
 */
@Configuration
public class KafkaTracingConfig {

    @Autowired(required = false)
    private Tracer tracer;

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object>
    kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);

        // Enable observation (tracing) for Kafka consumers
        factory.getContainerProperties()
                .setObservationEnabled(true);
        // observationEnabled: Spring Kafka automatically:
        // - Reads B3 trace headers from Kafka message
        // - Creates a child span in the ongoing trace
        // - Reports the span to Zipkin when processing completes

        return factory;
    }
}
