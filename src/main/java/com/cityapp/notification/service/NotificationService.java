package com.cityapp.notification.service;

import com.cityapp.common.event.*;
import com.cityapp.notification.entity.Notification;
import com.cityapp.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles all notification delivery: email, push, in-app, WebSocket.
 * Called by Kafka consumers.
 *
 * Each method is a fire-and-forget notification.
 * If email fails: log error and move on. Don't crash the consumer.
 * If push fails: log error. User will see the in-app notification instead.
 *
 * The Kafka consumer commits the offset AFTER this service returns.
 * If we throw: Kafka retries the same message.
 * So: either handle errors gracefully (log + continue) or throw deliberately
 * when we want a retry (e.g., temporary DB failure).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    // ── Order Notifications ───────────────────────────────────────────────────

    public void sendOrderCreatedPush(OrderCreatedEvent event) {
        // Phase 12: FCM push notification implementation
        log.info("Push notification (TODO Phase 12): " +
                        "Order #{} confirmed for userId={}",
                event.getOrderId(), event.getUserId());
    }

    public void sendNewOrderAlertToSeller(OrderCreatedEvent event) {
        // Notify the seller: new order arrived
        log.info("Seller alert: new order #{} at store {}",
                event.getOrderId(), event.getStoreName());
        createInAppNotification(
                event.getSellerId(),
                "New Order Received!",
                "Order #" + event.getOrderId() + " - ₹" +
                        event.getTotalAmount() + " is waiting for your confirmation.",
                "ORDER", event.getOrderId()
        );
    }

    public void sendStatusChangePush(Long userId, String title,
                                     String message, Long orderId) {
        log.info("Status push (TODO Phase 12): userId={} title='{}'", userId, title);
    }


    public void sendSellerOnboardingEmail(UserRegisteredEvent event) {
        log.info("Seller onboarding email (TODO): userId={}", event.getUserId());
    }


    // ── In-App Notifications ──────────────────────────────────────────────────

    @Transactional
    public void createInAppNotification(Long userId, String title,
                                        String body, String refType,
                                        Long refId) {
        Notification notification = Notification.builder()
                .userId(userId)
                .title(title)
                .body(body)
                .type(refType)
                .referenceId(refId)
                .referenceType(refType)
                .build();

        notificationRepository.save(notification);
        log.debug("In-app notification created: userId={} title='{}'", userId, title);

        // Phase 11: WebSocket push to connected buyers (real-time notification bell)
    }
}
