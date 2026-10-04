package com.cityapp.payment.controller;

import com.cityapp.common.response.ApiResponse;
import com.cityapp.payment.dto.InitiatePaymentRequest;
import com.cityapp.payment.dto.PaymentResponse;
import com.cityapp.payment.service.PaymentService;
import com.cityapp.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/initiate")
    public ResponseEntity<ApiResponse<PaymentResponse>> initiate(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody InitiatePaymentRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
                paymentService.initiatePayment(req, user.getId())));
    }
}