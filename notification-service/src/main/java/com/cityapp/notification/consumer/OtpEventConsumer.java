package com.cityapp.notification.consumer;

import com.cityapp.common.constants.AppConstants;
import com.cityapp.common.event.OtpRequestEvent;
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
public class OtpEventConsumer {

    private final NotificationService notificationService;
    private final StringRedisTemplate redisTemplate;

    @KafkaListener(
            topics  = AppConstants.TOPIC_OTP_REQUESTED,
            groupId = "notification-service-otp-requested"
    )
    public void onOtpRequested(@Payload OtpRequestEvent event) {
        log.info("Consuming otp.requested: email={}", event.getEmail());

        if (isAlreadyProcessed(event.getEventId(), "otp-requested-" + event.getEmail())) {
            log.debug("Duplicate otp.requested event: eventId={}. Skipping.", event.getEventId());
            return;
        }

        notificationService.sendOtpEmail(event);

        markAsProcessed(event.getEventId(), "otp-requested-" + event.getEmail());
    }

    private boolean isAlreadyProcessed(String eventId, String fallbackKey) {
        String key = AppConstants.REDIS_NOTIF_DEDUP_PREFIX +
                (eventId != null ? eventId : fallbackKey);
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    private void markAsProcessed(String eventId, String fallbackKey) {
        String key = AppConstants.REDIS_NOTIF_DEDUP_PREFIX +
                (eventId != null ? eventId : fallbackKey);
        redisTemplate.opsForValue().set(key, "1",
                86400, TimeUnit.SECONDS);
    }
}
