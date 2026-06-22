package com.cityapp.payment.controller;

import com.cityapp.payment.service.PaymentService;
import com.razorpay.Utils;
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
            Utils.verifyWebhookSignature(payload, signature, webhookSecret);
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
}