package com.cityapp.payment.service;

import com.cityapp.common.exception.AppException;
import com.cityapp.order.entity.Order;
import com.cityapp.order.entity.OrderStatus;
import com.cityapp.order.entity.OrderType;
import com.cityapp.order.repository.OrderRepository;
import com.cityapp.payment.dto.InitiatePaymentRequest;
import com.cityapp.payment.entity.Payment;
import com.cityapp.payment.repository.PaymentRepository;
import com.cityapp.payment.repository.PaymentWebhookEventRepository;
import com.cityapp.payment.entity.Payment.PaymentStatus;
import com.cityapp.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentWebhookEventRepository webhookEventRepository;

    @InjectMocks
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(paymentService, "webhookSecret", "test-webhook-secret");
    }

    @Test
    void initiatePayment_shouldRejectOrderOwnedByAnotherUser() {
        InitiatePaymentRequest request = request(100L, "COD");

        when(orderRepository.findByIdAndUserIdForUpdate(100L, 1L))
                .thenReturn(Optional.empty());

        AppException exception = assertThrows(
                AppException.class,
                () -> paymentService.initiatePayment(request, 1L));

        assertEquals(404, exception.getStatus().value());
        verify(paymentRepository, never()).existsByOrderIdAndStatus(anyLong(), any());
        verify(paymentRepository, never()).findTopByOrderIdOrderByCreatedAtDesc(anyLong());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void initiatePayment_shouldAllowUserToPayOwnConfirmedOrder() {
        Order order = confirmedOrder(100L, 1L);
        InitiatePaymentRequest request = request(100L, "COD");

        Payment savedPayment = Payment.builder()
                .id(500L)
                .order(order)
                .amount(order.getTotalAmount())
                .currency("INR")
                .method("COD")
                .status(PaymentStatus.PENDING)
                .build();

        when(orderRepository.findByIdAndUserIdForUpdate(100L, 1L))
                .thenReturn(Optional.of(order));
        when(paymentRepository.existsByOrderIdAndStatus(
                100L, PaymentStatus.SUCCESS))
                .thenReturn(false);
        when(paymentRepository.findTopByOrderIdOrderByCreatedAtDesc(100L))
                .thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class)))
                .thenReturn(savedPayment);

        var response = paymentService.initiatePayment(request, 1L);

        assertEquals(500L, response.getPaymentId());
        assertEquals(100L, response.getOrderId());
        assertEquals("COD", response.getMethod());
        assertEquals("PENDING", response.getStatus());
        assertEquals(order.getTotalAmount(), response.getAmount());
        verify(orderRepository).findByIdAndUserIdForUpdate(100L, 1L);
        verify(paymentRepository).save(any(Payment.class));
    }

    @Test
    void initiatePayment_shouldReturnNotFoundForMissingOrder() {
        InitiatePaymentRequest request = request(999L, "COD");

        when(orderRepository.findByIdAndUserIdForUpdate(999L, 1L))
                .thenReturn(Optional.empty());

        AppException exception = assertThrows(
                AppException.class,
                () -> paymentService.initiatePayment(request, 1L));

        assertEquals(404, exception.getStatus().value());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void initiatePayment_shouldRejectUnsupportedMethodBeforeLoadingOrder() {
        InitiatePaymentRequest request = request(100L, "UPI");

        AppException exception = assertThrows(
                AppException.class,
                () -> paymentService.initiatePayment(request, 1L));

        assertEquals(400, exception.getStatus().value());
        verifyNoInteractions(orderRepository, paymentRepository);
    }

    @Test
    void initiatePayment_shouldRejectDifferentMethodWhenPendingPaymentExists() {
        Order order = confirmedOrder(100L, 1L);
        Payment existing = Payment.builder()
                .id(500L)
                .order(order)
                .amount(order.getTotalAmount())
                .currency("INR")
                .method("COD")
                .status(PaymentStatus.PENDING)
                .build();

        when(orderRepository.findByIdAndUserIdForUpdate(100L, 1L))
                .thenReturn(Optional.of(order));
        when(paymentRepository.existsByOrderIdAndStatus(100L, PaymentStatus.SUCCESS))
                .thenReturn(false);
        when(paymentRepository.findTopByOrderIdOrderByCreatedAtDesc(100L))
                .thenReturn(Optional.of(existing));

        AppException exception = assertThrows(
                AppException.class,
                () -> paymentService.initiatePayment(request(100L, "RAZORPAY"), 1L));

        assertEquals(409, exception.getStatus().value());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void initiatePayment_shouldReusePendingPaymentWhenMethodMatchesIgnoringCase() {
        Order order = confirmedOrder(100L, 1L);
        Payment existing = Payment.builder()
                .id(500L)
                .order(order)
                .amount(order.getTotalAmount())
                .currency("INR")
                .method("COD")
                .status(PaymentStatus.PENDING)
                .build();

        when(orderRepository.findByIdAndUserIdForUpdate(100L, 1L))
                .thenReturn(Optional.of(order));
        when(paymentRepository.existsByOrderIdAndStatus(100L, PaymentStatus.SUCCESS))
                .thenReturn(false);
        when(paymentRepository.findTopByOrderIdOrderByCreatedAtDesc(100L))
                .thenReturn(Optional.of(existing));

        var response = paymentService.initiatePayment(request(100L, " cod "), 1L);

        assertEquals(500L, response.getPaymentId());
        assertEquals("COD", response.getMethod());
        assertEquals("PENDING", response.getStatus());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void webhook_shouldRejectInvalidSignature() {
        String body = capturedPayload("order_rzp_1", "pay_1", 49900);

        AppException exception = assertThrows(
                AppException.class,
                () -> paymentService.handleRazorpayWebhook(body, "invalid", "evt_1"));

        assertEquals(401, exception.getStatus().value());
        verifyNoInteractions(webhookEventRepository, paymentRepository);
    }

    @Test
    void webhook_shouldIgnoreDuplicateEvent() {
        String body = capturedPayload("order_rzp_1", "pay_1", 49900);
        when(webhookEventRepository.insertIfAbsent("evt_1", "payment.captured"))
                .thenReturn(0);

        paymentService.handleRazorpayWebhook(body, sign(body), "evt_1");

        verify(webhookEventRepository).insertIfAbsent("evt_1", "payment.captured");
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void webhook_shouldMarkPendingPaymentSuccessfulWhenAmountMatches() {
        String body = capturedPayload("order_rzp_1", "pay_1", 49900);
        Payment payment = Payment.builder()
                .id(10L)
                .amount(new BigDecimal("499.00"))
                .currency("INR")
                .method("RAZORPAY")
                .gatewayOrderId("order_rzp_1")
                .status(PaymentStatus.PENDING)
                .build();

        when(webhookEventRepository.insertIfAbsent("evt_1", "payment.captured"))
                .thenReturn(1);
        when(paymentRepository.findByGatewayOrderIdForUpdate("order_rzp_1"))
                .thenReturn(Optional.of(payment));

        paymentService.handleRazorpayWebhook(body, sign(body), "evt_1");

        assertEquals(PaymentStatus.SUCCESS, payment.getStatus());
        assertEquals("pay_1", payment.getGatewayPaymentId());
        assertNotNull(payment.getPaidAt());
        verify(paymentRepository).save(payment);
    }

    @Test
    void webhook_shouldRejectAmountMismatch() {
        String body = capturedPayload("order_rzp_1", "pay_1", 50000);
        Payment payment = Payment.builder()
                .id(10L)
                .amount(new BigDecimal("499.00"))
                .currency("INR")
                .method("RAZORPAY")
                .gatewayOrderId("order_rzp_1")
                .status(PaymentStatus.PENDING)
                .build();

        when(webhookEventRepository.insertIfAbsent("evt_1", "payment.captured"))
                .thenReturn(1);
        when(paymentRepository.findByGatewayOrderIdForUpdate("order_rzp_1"))
                .thenReturn(Optional.of(payment));

        AppException exception = assertThrows(
                AppException.class,
                () -> paymentService.handleRazorpayWebhook(body, sign(body), "evt_1"));

        assertEquals(400, exception.getStatus().value());
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        verify(paymentRepository, never()).save(any());
    }

    private static String capturedPayload(String orderId, String paymentId, long amount) {
        return """ 
                {"event":"payment.captured","payload":{"payment":{"entity":{"id":"%s","order_id":"%s","amount":%d,"currency":"INR","status":"captured"}}}}
                """.formatted(paymentId, orderId, amount);
    }

    private static String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("test-webhook-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static InitiatePaymentRequest request(Long orderId, String method) {
        InitiatePaymentRequest request = new InitiatePaymentRequest();
        request.setOrderId(orderId);
        request.setMethod(method);
        return request;
    }

    private static Order confirmedOrder(Long orderId, Long userId) {
        User user = User.builder()
                .id(userId)
                .name("Test User")
                .email("user" + userId + "@example.com")
                .passwordHash("hash")
                .build();

        return Order.builder()
                .id(orderId)
                .user(user)
                .status(OrderStatus.CONFIRMED)
                .orderType(OrderType.DELIVERY)
                .totalAmount(new BigDecimal("499.00"))
                .build();
    }
}
