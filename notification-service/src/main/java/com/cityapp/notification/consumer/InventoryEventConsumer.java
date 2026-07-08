package com.cityapp.notification.consumer;

import com.cityapp.common.constants.AppConstants;
import com.cityapp.common.event.InventoryLowEvent;
import com.cityapp.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryEventConsumer {

    private final NotificationService notificationService;
    private final StringRedisTemplate  redisTemplate;

    @KafkaListener(
            topics  = AppConstants.TOPIC_INVENTORY_LOW,
            groupId = "notification-service-inventory-low"
    )
    public void onInventoryLow(@Payload InventoryLowEvent event) {
        log.info("Consuming inventory.low: productId={} qty={}",
                event.getProductId(), event.getCurrentQuantity());

        /*
         * DEDUPLICATION FOR LOW STOCK ALERTS:
         *
         * Without dedup: every order that deducts inventory below threshold
         * publishes inventory.low. If 10 orders come in rapid succession:
         * 10 low-stock emails to the seller within 1 minute.
         * Seller is spammed. Email ignored or unsubscribed.
         *
         * With dedup: one alert per product per hour (1-hour TTL Redis key).
         * Seller gets meaningful alerts, not spam.
         *
         * The dedup key includes productId: each product has its own alert window.
         * Product A at low stock: alert sent. Key set for 1 hour.
         * Product B at low stock simultaneously: different key. Alert sent.
         * Product A again 30 minutes later: key still exists. Skipped.
         */
        String dedupKey = AppConstants.REDIS_LOW_STOCK_DEDUP + event.getProductId();

        Boolean isFirst = redisTemplate.opsForValue()
                .setIfAbsent(dedupKey, "1",
                        AppConstants.LOW_STOCK_DEDUP_HOURS, TimeUnit.HOURS);

        if (!Boolean.TRUE.equals(isFirst)) {
            log.debug("Low stock alert already sent for productId={} within last hour. Skipping.",
                    event.getProductId());
            return;
        }

        // First alert for this product in the last hour
        notificationService.sendLowStockAlert(event);
    }
}
