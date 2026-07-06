package com.cityapp.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cityapp.common.exception.AppException;
import com.cityapp.order.entity.Order;
import com.cityapp.order.entity.OrderStatus;
import com.cityapp.order.repository.OrderRepository;
import com.cityapp.payment.dto.InitiatePaymentRequest;
import com.cityapp.payment.dto.PaymentResponse;
import com.cityapp.payment.entity.Payment;
import com.cityapp.payment.repository.PaymentRepository;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PaymentService — the file that Phase 13 assumed existed.
 *
 * Handles two payment methods:
 *   COD: no external call, instant PENDING payment record
 *   RAZORPAY: creates a Razorpay order, returns details for the
 *             frontend to open the Razorpay checkout widget
 *
 * The actual payment CONFIRMATION happens via webhook (see
 * RazorpayWebhookController), not here. This method only INITIATES
 * the payment — it does not know if the buyer actually paid.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository   orderRepository;

    @Value("${cityapp.razorpay.key-id}")
    private String razorpayKeyId;

    @Value("${cityapp.razorpay.key-secret}")
    private String razorpayKeySecret;

    @CircuitBreaker(
            name = "razorpay",
            fallbackMethod = "initiatePaymentFallback"
    )
    @Retry(name = "razorpay")
    @Transactional
    public PaymentResponse initiatePayment(InitiatePaymentRequest req) {

        Order order = orderRepository.findByIdForUpdate(req.getOrderId())
                .orElseThrow(() ->
                        AppException.notFound(
                                "Order not found: " + req.getOrderId()));

        if (order.getStatus() != OrderStatus.CONFIRMED) {
            throw AppException.badRequest(
                    "Cannot initiate payment for order in status: "
                            + order.getStatus());
        }

        // Prevent duplicate successful payments
        if (paymentRepository.existsByOrderIdAndStatus(
                order.getId(),
                Payment.PaymentStatus.SUCCESS)) {

            throw AppException.conflict(
                    "Order has already been paid");
        }

        boolean alreadyPaid =
                paymentRepository.existsByOrderIdAndStatus(
                        order.getId(),
                        Payment.PaymentStatus.SUCCESS);

        if (alreadyPaid) {
            throw AppException.conflict("Order already paid");
        }

        // Check latest payment attempt
        Optional<Payment> latestPayment =
                paymentRepository.findTopByOrderIdOrderByCreatedAtDesc(
                        order.getId());

        if (latestPayment.isPresent()) {

            Payment existing = latestPayment.get();

            switch (existing.getStatus()) {

                case SUCCESS -> {
                    throw AppException.conflict(
                            "Order has already been paid");
                }

                case PENDING -> {

                    log.info(
                            "Returning existing pending payment. orderId={} paymentId={}",
                            order.getId(),
                            existing.getId()
                    );

                    // Idempotent response
                    return buildResponse(existing);
                }

                case FAILED, REFUNDED -> {

                    log.info(
                            "Creating new payment attempt. orderId={} previousPaymentId={} status={}",
                            order.getId(),
                            existing.getId(),
                            existing.getStatus()
                    );

                }
            }
        }

        // New payment attempt

        if ("COD".equalsIgnoreCase(req.getMethod())) {
            return createCodPayment(order);
        }

        return createRazorpayPayment(order);
    }

    private PaymentResponse createCodPayment(Order order) {
        Payment payment = Payment.builder()
                .order(order)
                .amount(order.getTotalAmount())
                .currency("INR")
                .method("COD")
                .status(Payment.PaymentStatus.PENDING)
                .build();

        Payment saved = paymentRepository.save(payment);
        log.info("COD payment created: orderId={}", order.getId());

        return PaymentResponse.builder()
                .paymentId(saved.getId())
                .orderId(order.getId())
                .method("COD")
                .status("PENDING")
                .amount(saved.getAmount())
                .build();
    }

    private PaymentResponse createRazorpayPayment(Order order) {
        try {
            RazorpayClient client = new RazorpayClient(razorpayKeyId, razorpayKeySecret);

            // Razorpay amounts are in paise, not rupees
            long amountInPaise = order.getTotalAmount()
                    .multiply(BigDecimal.valueOf(100))
                    .longValueExact();

            JSONObject orderRequest = new JSONObject();
            orderRequest.put("amount", amountInPaise);
            orderRequest.put("currency", "INR");
            orderRequest.put("receipt", "cityapp_order_" + order.getId());
            orderRequest.put("payment_capture", 1);
            // payment_capture=1: auto-capture on successful payment
            // (no separate "capture" API call needed)

            com.razorpay.Order rzpOrder = client.orders.create(orderRequest);

            Payment payment = Payment.builder()
                    .order(order)
                    .amount(order.getTotalAmount())
                    .currency("INR")
                    .method("RAZORPAY")
                    .status(Payment.PaymentStatus.PENDING)
                    .gatewayOrderId(rzpOrder.get("id"))
                    .build();

            Payment saved = paymentRepository.save(payment);
            log.info("Razorpay order created: orderId={} razorpayOrderId={}",
                    order.getId(), rzpOrder.get("id").toString());

            return PaymentResponse.builder()
                    .paymentId(saved.getId())
                    .orderId(order.getId())
                    .method("RAZORPAY")
                    .status("PENDING")
                    .amount(saved.getAmount())
                    .razorpayOrderId(rzpOrder.get("id"))
                    .razorpayKeyId(razorpayKeyId)  // frontend needs this to open checkout
                    .build();

        } catch (RazorpayException e) {
            // @Retry catches this and retries up to 2 times.
            // If still failing: @CircuitBreaker counts it as a failure.
            // After threshold: circuit opens, fallback is called.
            throw new RuntimeException("Razorpay order creation failed: " + e.getMessage(), e);
        }
    }

    public PaymentResponse initiatePaymentFallback(
            InitiatePaymentRequest req,
            Throwable throwable) {

        log.error(
                "Razorpay unavailable for orderId={}",
                req.getOrderId(),
                throwable
        );

        throw AppException.serviceUnavailable(
                "Payment provider temporarily unavailable. Please try again later."
        );
    }

    /**
     * Called by the webhook handler after verifying the signature.
     * This is the ACTUAL confirmation that money changed hands.
     */
    @Transactional
    public void confirmRazorpayPayment(String razorpayOrderId,
                                       String razorpayPaymentId) {
        Payment payment = paymentRepository.findByGatewayOrderId(razorpayOrderId)
                .orElseThrow(() -> AppException.notFound(
                        "Payment not found for razorpayOrderId=" + razorpayOrderId));

        payment.setGatewayPaymentId(razorpayPaymentId);
        payment.setStatus(Payment.PaymentStatus.SUCCESS);
        payment.setPaidAt(Instant.now());
        paymentRepository.save(payment);

        log.info("Payment confirmed: orderId={} razorpayPaymentId={}",
                payment.getOrder().getId(), razorpayPaymentId);
    }

    private PaymentResponse buildResponse(Payment payment) {

        boolean isRazorpay =
                "RAZORPAY".equalsIgnoreCase(payment.getMethod());

        return PaymentResponse.builder()
                .paymentId(payment.getId())
                .orderId(payment.getOrder().getId())
                .method(payment.getMethod())
                .status(payment.getStatus().name())
                .amount(payment.getAmount())
                .razorpayOrderId(
                        isRazorpay
                                ? payment.getGatewayOrderId()
                                : null
                )
                .razorpayKeyId(
                        isRazorpay
                                ? razorpayKeyId
                                : null
                )
                .build();
    }
}