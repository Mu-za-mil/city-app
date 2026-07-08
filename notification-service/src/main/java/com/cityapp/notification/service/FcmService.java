package com.cityapp.notification.service;

import com.cityapp.notification.entity.DeviceToken;
import com.cityapp.notification.repository.DeviceTokenRepository;
import com.google.firebase.messaging.*;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Handles all Firebase Cloud Messaging (FCM) push notifications.
 *
 * PUSH NOTIFICATION LIFECYCLE:
 *   1. User installs the app. App requests FCM token from Firebase.
 *   2. FCM returns a registration token (device identifier).
 *   3. App sends this token to our server: POST /api/v1/device-tokens
 *   4. We store the token in device_tokens table.
 *   5. When we want to push: call FCM API with the token.
 *   6. FCM delivers to the specific device.
 *
 * MULTICAST (send to multiple devices):
 *   A user may have: Android phone + iPad + web browser.
 *   THREE FCM tokens. All registered.
 *   Multicast: send one FCM request → FCM delivers to all three.
 *   More efficient than three separate FCM calls.
 *   FCM limit: 500 tokens per multicast request.
 *
 * TOKEN MANAGEMENT:
 *   FCM tokens expire and rotate.
 *   When we push with an expired token: FCM returns UNREGISTERED error.
 *   We: deactivate the token in DB.
 *   The device will register a new token next time the app opens.
 *
 * GRACEFUL DEGRADATION:
 *   If FirebaseMessaging bean is null (credentials not configured):
 *   All FCM methods return immediately without doing anything.
 *   Email and in-app notifications are not affected.
 *   The system works without FCM. Just no push notifications.
 */
@Slf4j
@Service
public class FcmService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final FirebaseMessaging     firebaseMessaging;

    @Autowired(required = false)   // optional
    public FcmService(DeviceTokenRepository deviceTokenRepository,
                      FirebaseMessaging firebaseMessaging) {
        this.deviceTokenRepository = deviceTokenRepository;
        this.firebaseMessaging = firebaseMessaging;
        if (this.firebaseMessaging == null) {
            log.warn("FirebaseMessaging bean is null – FCM push notifications disabled");
        }
    }
    // Spring injects null here if FirebaseConfig returned null.
    // We null-check before every FCM call.


    /**
     * Send a push notification to ALL devices registered to a user.
     *
     * @param userId        the recipient's user ID
     * @param title         notification title (appears in system tray)
     * @param body          notification body text
     * @param data          optional key-value pairs (app can read these in background)
     */
    @CircuitBreaker(name = "fcm", fallbackMethod = "sendToUserFallback")
    public void sendToUser(Long userId, String title, String body,
                           Map<String, String> data) {
        if (firebaseMessaging == null) {
            log.debug("FCM not configured. Skipping push to userId={}", userId);
            return;
        }

        List<DeviceToken> tokens = deviceTokenRepository
                .findByUserIdAndActiveTrue(userId);

        if (tokens.isEmpty()) {
            log.debug("No active devices for userId={}", userId);
            return;
        }

        // Build notification payload
        Notification notification = Notification.builder()
                .setTitle(title)
                .setBody(body)
                .build();

        // Collect token strings
        List<String> tokenStrings = tokens.stream()
                .map(DeviceToken::getToken)
                .collect(Collectors.toList());

        // Use MulticastMessage for multiple devices (up to 500 per call)
        MulticastMessage message = MulticastMessage.builder()
                .setNotification(notification)
                .putAllData(data != null ? data : Map.of())
                .addAllTokens(tokenStrings)
                .build();

        try {
            BatchResponse response = firebaseMessaging.sendEachForMulticast(message);

            log.info("FCM multicast: userId={} sent={} success={} failure={}",
                    userId, tokenStrings.size(),
                    response.getSuccessCount(),
                    response.getFailureCount());

            // Handle individual token failures
            handleMulticastResponse(response, tokenStrings);

        } catch (FirebaseMessagingException e) {
            log.error("FCM multicast failed for userId={}: {}", userId, e.getMessage());
        }
    }

    /**
     * Fallback when FCM circuit is OPEN or FCM call fails.
     * Gracefully degrades – push notification is skipped, but the business
     * operation (order placement, etc.) continues unaffected.
     */
    public void sendToUserFallback(Long userId, String title, String body,
                                   Map<String, String> data, Throwable t) {
        log.warn("FCM circuit open or failed for userId={}. Push skipped. " +
                        "In-app notification/email still sent. Reason: {}",
                userId, t != null ? t.getClass().getSimpleName() : "unknown");
        // No exception thrown – the business operation continues.
        // The caller (NotificationService) will still create in-app notification.
    }
    
    /**
     * Handle per-token responses from FCM.
     * Deactivate tokens that FCM says are UNREGISTERED.
     *
     * WHY PER-TOKEN HANDLING:
     *   User has 3 devices. Device 2 was uninstalled.
     *   FCM: device 1 OK, device 2 UNREGISTERED, device 3 OK.
     *   We must deactivate device 2's token.
     *   Next push: only devices 1 and 3 receive it.
     */
    private void handleMulticastResponse(BatchResponse response,
                                         List<String> tokens) {
        List<SendResponse> responses = response.getResponses();
        for (int i = 0; i < responses.size(); i++) {
            SendResponse sendResponse = responses.get(i);
            if (!sendResponse.isSuccessful()) {
                FirebaseMessagingException ex = sendResponse.getException();
                if (ex != null && ex.getMessagingErrorCode() ==
                        MessagingErrorCode.UNREGISTERED) {
                    // Token is no longer valid — device uninstalled, token rotated
                    String expiredToken = tokens.get(i);
                    deviceTokenRepository.deactivateToken(expiredToken);
                    log.info("Deactivated expired FCM token: {}...",
                            expiredToken.substring(0, 20));
                }
            }
        }
    }

    /**
     * Send to a Kafka topic for fan-out.
     * Used for: "send to all followers of store X" (Phase 16+).
     * For now: placeholder for Phase 15 when we have proper fan-out.
     */
    public void sendToTopic(String topic, String title, String body) {
        if (firebaseMessaging == null) return;

        Message message = Message.builder()
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .setTopic(topic)
                .build();

        try {
            String messageId = firebaseMessaging.send(message);
            log.debug("FCM topic push sent: topic={} messageId={}", topic, messageId);
        } catch (FirebaseMessagingException e) {
            log.error("FCM topic push failed: topic={} error={}", topic, e.getMessage());
        }
    }
}