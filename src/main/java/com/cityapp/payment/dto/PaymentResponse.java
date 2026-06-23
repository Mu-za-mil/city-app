package com.cityapp.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {
    private Long paymentId;
    private Long orderId;
    private String method;
    private String status;
    private BigDecimal amount;
    private String razorpayOrderId;  // null for COD
    private String razorpayKeyId;    // needed by frontend to open checkout widget
}