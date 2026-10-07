package com.cityapp.payment.controller;

import com.cityapp.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments/webhooks")
@RequiredArgsConstructor
public class RazorpayWebhookController {
    private final PaymentService paymentService;

    @PostMapping("/razorpay")
    public ResponseEntity<Void> handle(
            @RequestHeader("X-Razorpay-Signature") String signature,
            @RequestHeader("X-Razorpay-Event-Id") String eventId,
            @RequestBody String rawBody) {
        paymentService.handleRazorpayWebhook(rawBody, signature, eventId);
        return ResponseEntity.ok().build();
    }
}
