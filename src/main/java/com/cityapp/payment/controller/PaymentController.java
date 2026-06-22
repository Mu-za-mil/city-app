package com.cityapp.payment.controller;

import com.cityapp.common.response.ApiResponse;
import com.cityapp.payment.dto.InitiatePaymentRequest;
import com.cityapp.payment.dto.PaymentResponse;
import com.cityapp.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/initiate")
    public ResponseEntity<ApiResponse<PaymentResponse>> initiate(
            @Valid @RequestBody InitiatePaymentRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
                paymentService.initiatePayment(req)));
    }
}