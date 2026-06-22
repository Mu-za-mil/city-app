package com.cityapp.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InitiatePaymentRequest {
    @NotNull
    private Long orderId;
    @NotBlank
    private String method; // "RAZORPAY" or "COD"
}