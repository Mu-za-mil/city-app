package com.cityapp.payment.service;

import com.cityapp.common.exception.AppException;
import com.cityapp.order.entity.Order;
import com.cityapp.order.entity.OrderStatus;
import com.cityapp.order.entity.OrderType;
import com.cityapp.order.repository.OrderRepository;
import com.cityapp.payment.dto.InitiatePaymentRequest;
import com.cityapp.payment.entity.Payment;
import com.cityapp.payment.repository.PaymentRepository;
import com.cityapp.payment.entity.Payment.PaymentStatus;
import com.cityapp.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private PaymentService paymentService;

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
