package com.cityapp.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONObject;

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
import com.cityapp.payment.repository.PaymentWebhookEventRepository;
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
    private final PaymentWebhookEventRepository webhookEventRepository;

    @Value("${cityapp.razorpay.key-id}")
    private String razorpayKeyId;

    @Value("${cityapp.razorpay.key-secret}")
    private String razorpayKeySecret;

    @Value("${cityapp.razorpay.webhook-secret}")
    private String webhookSecret;

    @CircuitBreaker(
            name = "razorpay",
            fallbackMethod = "initiatePaymentFallback"
    )
    @Retry(name = "razorpay")
    @Transactional
    public PaymentResponse initiatePayment(
            InitiatePaymentRequest req,
            Long userId) {

        Order order = orderRepository.findByIdAndUserIdForUpdate(
                        req.getOrderId(), userId)
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
    public void handleRazorpayWebhook(String rawBody, String signature, String eventId) {
        verifyWebhookSignature(rawBody, signature);

        if (eventId == null || eventId.isBlank()) {
            throw AppException.badRequest("Missing Razorpay event ID");
        }

        JSONObject payload = new JSONObject(rawBody);
        String eventType = payload.optString("event", "");
        int inserted = webhookEventRepository.insertIfAbsent(eventId, eventType);
        if (inserted == 0) {
            log.info("Ignoring duplicate Razorpay webhook eventId={}", eventId);
            return;
        }

        if (!"payment.captured".equals(eventType) && !"payment.failed".equals(eventType)) {
            log.info("Ignoring unsupported Razorpay event type={} eventId={}", eventType, eventId);
            return;
        }

        JSONObject entity = payload.getJSONObject("payload")
                .getJSONObject("payment")
                .getJSONObject("entity");
        String razorpayOrderId = entity.getString("order_id");
        String razorpayPaymentId = entity.getString("id");
        long amountPaise = entity.getLong("amount");
        String currency = entity.getString("currency");

        Payment payment = paymentRepository.findByGatewayOrderIdForUpdate(razorpayOrderId)
                .orElseThrow(() -> AppException.notFound("Payment not found for Razorpay order"));

        if (!"RAZORPAY".equalsIgnoreCase(payment.getMethod())) {
            throw AppException.badRequest("Webhook does not belong to a Razorpay payment");
        }
        if (payment.getAmount().movePointRight(2).longValueExact() != amountPaise) {
            throw AppException.badRequest("Webhook amount does not match payment amount");
        }
        if (!payment.getCurrency().equalsIgnoreCase(currency)) {
            throw AppException.badRequest("Webhook currency does not match payment currency");
        }

        if ("payment.captured".equals(eventType)) {
            if (payment.getStatus() == Payment.PaymentStatus.SUCCESS) {
                return;
            }
            if (payment.getStatus() != Payment.PaymentStatus.PENDING) {
                log.warn("Ignoring captured event for paymentId={} status={}", payment.getId(), payment.getStatus());
                return;
            }
            payment.setGatewayPaymentId(razorpayPaymentId);
            payment.setStatus(Payment.PaymentStatus.SUCCESS);
            payment.setPaidAt(Instant.now());
            paymentRepository.save(payment);
        } else if (payment.getStatus() == Payment.PaymentStatus.PENDING) {
            payment.setGatewayPaymentId(razorpayPaymentId);
            payment.setStatus(Payment.PaymentStatus.FAILED);
            paymentRepository.save(payment);
        }
    }

    private void verifyWebhookSignature(String rawBody, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            byte[] received = java.util.HexFormat.of().parseHex(signature);
            if (!MessageDigest.isEqual(expected, received)) {
                throw AppException.unauthorized("Invalid Razorpay webhook signature");
            }
        } catch (IllegalArgumentException e) {
            throw AppException.unauthorized("Invalid Razorpay webhook signature");
        } catch (Exception e) {
            throw new IllegalStateException("Unable to verify Razorpay webhook signature", e);
        }
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