package com.cityapp.notification.consumer;

import com.cityapp.common.constants.AppConstants;
import com.cityapp.common.event.UserRegisteredEvent;
import com.cityapp.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventConsumer {

    private final NotificationService notificationService;

    @KafkaListener(
            topics  = AppConstants.TOPIC_USER_REGISTERED,
            groupId = "notification-service-user-registered"
    )
    public void onUserRegistered(@Payload UserRegisteredEvent event) {
        log.info("Consuming user.registered: userId={}", event.getUserId());

        // Welcome email
        notificationService.sendWelcomeEmail(event);

        // If seller: send "your store setup guide" email
        if (event.getRole() != null &&
                event.getRole().name().equals("SELLER")) {
            notificationService.sendSellerOnboardingEmail(event);
        }

        // Create welcome in-app notification
        notificationService.createInAppNotification(
                event.getUserId(),
                "Welcome to City App!",
                "Hi " + event.getName() +
                        "! Your account is ready. Start exploring nearby stores.",
                "WELCOME", null
        );
    }
}
