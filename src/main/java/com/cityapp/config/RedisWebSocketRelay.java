package com.cityapp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Redis Pub/Sub relay for WebSocket messages across multiple server instances.
 *
 * THE MULTI-INSTANCE PROBLEM:
 *
 *   Without relay (single instance is fine):
 *   Instance A: has buyer B's WebSocket connection.
 *   Instance B: wants to push delivery location to buyer B.
 *   Instance B's in-memory broker: buyer B not subscribed here.
 *   Message dropped. Buyer B never receives it.
 *
 *   With Redis Pub/Sub relay:
 *   Instance B: publishes to Redis channel "ws:delivery:buyerB"
 *   Instance A: subscribed to "ws:delivery:*"
 *   Instance A receives the Redis message.
 *   Instance A pushes to buyer B's WebSocket connection.
 *   Message delivered!
 *
 * FLOW:
 *   DeliveryService wants to push location to buyer B:
 *   1. Calls relay.sendToUser(userId, locationDto)
 *   2. relay sends via local WebSocket (catches buyer if on THIS instance)
 *   3. relay publishes to Redis channel "ws:delivery:{userId}"
 *   4. ALL instances receive from Redis
 *   5. Each instance: does buyer have a WebSocket on THIS instance?
 *      YES → push to their WebSocket
 *      NO  → ignore (buyer is on another instance, already received via step 2)
 *
 * REDIS CHANNEL NAMING: "ws:notifications:{userId}"
 *   Prefix: "ws:" separates from other Redis keys.
 *   "notifications": the type of WebSocket message.
 *   "{userId}": which user's WebSocket to target.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisWebSocketRelay {

    public static final String DELIVERY_CHANNEL_PREFIX = "ws:delivery:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final SimpMessagingTemplate         messagingTemplate;
    private final ObjectMapper                  objectMapper;

    @Value("${app.websocket.redis-relay-enabled:false}")
    private boolean relayEnabled;

    /**
     * Send a delivery location update to a specific buyer.
     * Delivers to local WebSocket AND publishes to Redis for other instances.
     */
    public void sendDeliveryLocation(String buyerPrincipal, Object locationPayload) {
        
        log.debug(">>> Sending location to buyerPrincipal={}", buyerPrincipal);
        // 1. Send to buyer's WebSocket on THIS instance
        try {
            messagingTemplate.convertAndSendToUser(
                    buyerPrincipal,
                    "/queue/delivery",
                    locationPayload
            );
        } catch (Exception e) {
            log.debug("Local WebSocket delivery failed for principal={}: {}",
                    buyerPrincipal, e.getMessage());
        }

        // 2. Publish to Redis → other instances will deliver to their local connections
        if (relayEnabled) {
            try {
                String payload = objectMapper.writeValueAsString(locationPayload);
                redisTemplate.convertAndSend(
                        DELIVERY_CHANNEL_PREFIX + buyerPrincipal, payload);
            } catch (Exception e) {
                log.warn("Redis relay publish failed for principal={}: {}",
                        buyerPrincipal, e.getMessage());
            }
        }
    }

    /**
     * Redis Pub/Sub listener container.
     * Subscribes to all delivery location channels.
     * When a message arrives: push to buyer's local WebSocket (if connected here).
     */
    @Bean
    public RedisMessageListenerContainer redisListenerContainer(
            RedisConnectionFactory factory) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);

        if (relayEnabled) {
            container.addMessageListener(
                    (message, pattern) -> {
                        try {
                            String channel = new String(message.getChannel());
                            String buyerPrincipal = channel.replace(DELIVERY_CHANNEL_PREFIX, "");
                            String body    = new String(message.getBody());

                            // Push to buyer's local WebSocket if they're connected here
                            messagingTemplate.convertAndSendToUser(
                                    buyerPrincipal, "/queue/delivery", body);

                            log.debug("Redis relay delivered location to buyerPrincipal={}", buyerPrincipal);
                        } catch (Exception e) {
                            log.warn("Redis relay listener error: {}", e.getMessage());
                        }
                    },
                    new PatternTopic(DELIVERY_CHANNEL_PREFIX + "*")
            );
            log.info("WebSocket Redis relay listener registered");
        }

        return container;
    }
}