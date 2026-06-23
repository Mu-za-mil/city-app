package com.cityapp.payment.controller;

import com.cityapp.payment.service.PaymentService;
import com.razorpay.Utils;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks/razorpay")
@RequiredArgsConstructor
public class RazorpayWebhookController {

    private final PaymentService paymentService;

    @Value("${cityapp.razorpay.webhook-secret}")
    private String webhookSecret;

    @PostMapping
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("X-Razorpay-Signature") String signature) {

        // CRITICAL: verify this request genuinely came from Razorpay.
        // Without this check, anyone who knows your webhook URL can
        // POST a fake "payment succeeded" event and get free orders.
        try {
            // Prefer an explicit verification here so we can control
            // behavior when the secret is missing or verification fails.
            verifySignature(payload, signature);
        } catch (Exception e) {
            log.error("Razorpay webhook signature verification FAILED. " +
                    "Possible spoofed request.");
            return ResponseEntity.status(400).build();
        }

        JSONObject event = new JSONObject(payload);
        String eventType = event.getString("event");

        if ("payment.captured".equals(eventType)) {
            JSONObject paymentEntity = event.getJSONObject("payload")
                    .getJSONObject("payment").getJSONObject("entity");

            String razorpayOrderId   = paymentEntity.getString("order_id");
            String razorpayPaymentId = paymentEntity.getString("id");

            paymentService.confirmRazorpayPayment(razorpayOrderId, razorpayPaymentId);
        }

        // Razorpay retries the webhook for 24 hours if you don't return 200
        return ResponseEntity.ok().build();
    }

    private void verifySignature(String payload, String signature) throws Exception {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.error("Razorpay webhook secret is not configured");
            throw new IllegalStateException("webhook secret not configured");
        }

        Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
        SecretKeySpec secret_key = new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        sha256_HMAC.init(secret_key);
        byte[] hash = sha256_HMAC.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        String generated = bytesToHex(hash);

        if (!secureEquals(generated, signature)) {
            log.warn("Razorpay webhook signature mismatch. expected={} received={}", generated, signature);
            throw new Exception("Invalid signature");
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static boolean secureEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        if (aBytes.length != bBytes.length) return false;
        int result = 0;
        for (int i = 0; i < aBytes.length; i++) {
            result |= aBytes[i] ^ bBytes[i];
        }
        return result == 0;
    }
}