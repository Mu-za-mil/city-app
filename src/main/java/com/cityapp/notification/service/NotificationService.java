package com.cityapp.notification.service;

import com.cityapp.common.event.*;
import com.cityapp.notification.entity.Notification;
import com.cityapp.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

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
    private final EmailService           emailService;
    private final FcmService             fcmService;

    // ── Order Notifications ───────────────────────────────────────────────────

    public void sendOrderConfirmationEmail(OrderCreatedEvent event) {
        try {
            emailService.sendOrderConfirmationFull(event);
        } catch (Exception e) {
            log.error("Failed to send order confirmation email: orderId={} error={}",
                    event.getOrderId(), e.getMessage());
            // Don't rethrow: email failure should not cause Kafka retry.
        }
    }

    public void sendOrderCreatedPush(OrderCreatedEvent event) {
        // Push to buyer's devices
        fcmService.sendToUser(
                event.getUserId(),
                "Order Confirmed! ✅",
                "Your order from " + event.getStoreName() +
                        " — ₹" + event.getTotalAmount() + " is confirmed.",
                Map.of(
                        "type",    "ORDER_CONFIRMED",
                        "orderId", String.valueOf(event.getOrderId())
                )
        );
    }


    public void sendNewOrderAlertToSeller(OrderCreatedEvent event) {
        // Push to seller's devices
        fcmService.sendToUser(
                event.getSellerId(),
                "New Order! 🛒",
                "Order #" + event.getOrderId() +
                        " — ₹" + event.getTotalAmount() + " is waiting.",
                Map.of(
                        "type",    "NEW_ORDER",
                        "orderId", String.valueOf(event.getOrderId())
                )
        );

        createInAppNotification(
                event.getSellerId(),
                "New Order Received! 🛒",
                "Order #" + event.getOrderId() + " — ₹" +
                        event.getTotalAmount() + " is waiting for your action.",
                "ORDER", event.getOrderId()
        );
    }

    public void sendStatusChangePush(Long userId, String title,
                                     String message, Long orderId) {
        fcmService.sendToUser(
                userId,
                title,
                message,
                Map.of("type", "ORDER_STATUS", "orderId", String.valueOf(orderId))
        );
    }

    // ── User Notifications ────────────────────────────────────────────────────

    public void sendWelcomeEmail(UserRegisteredEvent event) {
        try {
            emailService.sendWelcomeEmail(event.getEmail(), event.getName());
        } catch (Exception e) {
            log.error("Failed to send welcome email: userId={} error={}",
                    event.getUserId(), e.getMessage());
        }
    }

    public void sendSellerOnboardingEmail(UserRegisteredEvent event) {
        emailService.sendSellerOnboardingEmail(event.getEmail(), event.getName());
    }

    // ── Inventory Notifications ───────────────────────────────────────────────

    public void sendLowStockAlert(InventoryLowEvent event) {
        // Push to seller
        fcmService.sendToUser(
                event.getSellerId(),
                "⚠️ Low Stock Alert",
                "'" + event.getProductName() + "' — only " +
                        event.getCurrentQuantity() + " units left!",
                Map.of(
                        "type",      "LOW_STOCK",
                        "productId", String.valueOf(event.getProductId())
                )
        );

        // Email to seller
        emailService.sendLowStockEmail(
                event.getSellerId(),
                event.getProductName(),
                event.getCurrentQuantity()
        );

        // In-app notification
        createInAppNotification(
                event.getSellerId(),
                "⚠️ Low Stock: " + event.getProductName(),
                "Only " + event.getCurrentQuantity() + " units remaining " +
                        "(threshold: " + event.getThreshold() + "). Restock soon!",
                "INVENTORY",
                event.getProductId()
        );
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

    }
}
