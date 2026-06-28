package com.cityapp.notification.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.util.Map;

/**
 * SMS delivery via MSG91 (popular Indian SMS gateway).
 *
 * WHY SMS IN ADDITION TO PUSH:
 *   Push notifications: require app installed + internet + user opted-in.
 *   SMS: works on any phone, any network, no app needed.
 *
 *   Critical situations where SMS is used:
 *   - OTP verification: phone may not have data, just cellular
 *   - Delivery confirmation: buyer may have uninstalled app
 *   - Payment confirmation: financial record via non-app channel
 *
 * MSG91:
 *   Indian SMS gateway. Competitive pricing. High deliverability.
 *   API: simple REST POST request.
 *   Handles: operator routing, NDNC compliance, DLT registration.
 *   DLT (Distributed Ledger Technology): TRAI mandate.
 *     All SMS template IDs must be registered on India's DLT portal.
 *     Unregistered templates: blocked by operators.
 *
 * OTP_ENABLED FLAG:
 *   In development: disable SMS. OTP is logged and emailed to MailHog.
 *   In production: enable SMS with real MSG91 credentials.
 *   Toggle: OTP_SMS_ENABLED=true in .env
 */
@Slf4j
@Service
public class SmsService {

    @Value("${cityapp.otp.sms-enabled:false}")
    private boolean smsEnabled;

    @Value("${cityapp.msg91.auth-key:}")
    private String msg91AuthKey;

    @Value("${cityapp.msg91.template-id:}")
    private String msg91TemplateId;

    @Value("${cityapp.msg91.sender-id:CITYAP}")
    private String senderId;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Send an OTP to a phone number.
     * In dev: logs the OTP. In prod: sends real SMS.
     */
    @CircuitBreaker(name = "msg91", fallbackMethod = "sendOtpFallback")
    public void sendOtp(String phone, String otp) {
        if (!smsEnabled) {
            // Development: print OTP to console
            log.info("=== OTP (DEV MODE) === Phone: {} OTP: {} =====", phone, otp);
            return;
        }

        if (msg91AuthKey.isBlank()) {
            log.warn("MSG91_AUTH_KEY not configured. SMS not sent.");
            return;
        }

        try {
            String url = "https://api.msg91.com/api/v5/otp";

            Map<String, Object> body = Map.of(
                    "authkey",     msg91AuthKey,
                    "mobile",      "91" + phone,  // Indian number with country code
                    "template_id", msg91TemplateId,
                    "otp",         otp,
                    "sender",      senderId
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("OTP SMS sent: phone={}", phone);
            } else {
                log.error("OTP SMS failed: phone={} status={}", phone, response.getStatusCode());
            }

        } catch (Exception e) {
            log.error("OTP SMS error: phone={} error={}", phone, e.getMessage());
            // Don't rethrow: fall back to email OTP.
        }
    }

    public void sendOtpFallback(String phone, String otp, Throwable t) {
        log.error("MSG91 circuit open. SMS OTP not sent to {}. " +
                "Email OTP will be sent instead. Reason: {}", phone, t.getMessage());
        // The email OTP path is separate — it still works.
        // User receives OTP via email even when SMS fails.
    }


    /**
     * Send a delivery confirmation SMS.
     */
    public void sendDeliveryConfirmation(String phone, Long orderId) {
        if (!smsEnabled) {
            log.info("=== SMS (DEV) === Delivery confirmed for order #{}", orderId);
            return;
        }

        // In production: use MSG91 Flow API with registered template
        // Template: "Your order #{orderId} has been delivered. Rate your experience: {link}"
        sendTransactional(phone,
                "Your order #" + orderId + " has been delivered successfully! " +
                        "Rate your experience on City App. - CITYAP");
    }

    private void sendTransactional(String phone, String message) {
        try {
            // MSG91 transactional SMS API
            String url = String.format(
                    "https://api.msg91.com/api/sendhttp.php?" +
                            "authkey=%s&mobiles=91%s&message=%s&route=4&sender=%s&country=91",
                    msg91AuthKey,
                    phone,
                    java.net.URLEncoder.encode(message, "UTF-8"),
                    senderId
            );

            String response = restTemplate.getForObject(url, String.class);
            log.info("SMS sent: phone={} response={}", phone, response);

        } catch (Exception e) {
            log.error("SMS send error: phone={} error={}", phone, e.getMessage());
        }
    }
}
