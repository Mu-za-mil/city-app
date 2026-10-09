package com.cityapp.order.service;

import com.cityapp.common.exception.AppException;
import com.cityapp.order.dto.OrderResponse;
import com.cityapp.order.dto.PlaceOrderRequest;
import com.cityapp.order.entity.Order;
import com.cityapp.order.mapper.OrderMapper;
import com.cityapp.order.repository.OrderRepository;
import com.cityapp.payment.repository.PaymentRepository;
import com.cityapp.product.repository.InventoryRepository;
import com.cityapp.product.repository.ProductRepository;
import com.cityapp.store.repository.StoreRepository;
import com.cityapp.user.entity.User;
import com.cityapp.common.event.EventPublisher;
import com.cityapp.outbox.service.OutboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderIdempotencyOwnershipTest {

    @Mock OrderRepository orderRepository;
    @Mock ProductRepository productRepository;
    @Mock InventoryRepository inventoryRepository;
    @Mock StoreRepository storeRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock OrderMapper orderMapper;
    @Mock EventPublisher eventPublisher;
    @Mock OutboxService outboxService;

    @InjectMocks OrderService orderService;

    @Test
    void sameBuyerRetry_returnsExistingOrder() {
        User buyer = mock(User.class);
        when(buyer.getId()).thenReturn(10L);

        Order existingOrder = mock(Order.class);
        when(existingOrder.getUser()).thenReturn(buyer);
        when(existingOrder.getId()).thenReturn(100L);
        when(orderRepository.findByIdempotencyKey("checkout-key"))
                .thenReturn(Optional.of(existingOrder));

        OrderResponse expected = OrderResponse.builder().id(100L).build();
        when(orderMapper.toResponse(existingOrder)).thenReturn(expected);
        when(paymentRepository.findByOrderId(100L)).thenReturn(Optional.empty());

        PlaceOrderRequest request = PlaceOrderRequest.builder()
                .idempotencyKey("checkout-key")
                .build();

        OrderResponse actual = orderService.placeOrder(buyer, request);

        assertEquals(100L, actual.getId());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void differentBuyerUsingExistingKey_conflictsWithoutReturningOrder() {
        User originalBuyer = mock(User.class);
        when(originalBuyer.getId()).thenReturn(10L);

        User attacker = mock(User.class);
        when(attacker.getId()).thenReturn(20L);

        Order existingOrder = mock(Order.class);
        when(existingOrder.getUser()).thenReturn(originalBuyer);
        when(orderRepository.findByIdempotencyKey("checkout-key"))
                .thenReturn(Optional.of(existingOrder));

        PlaceOrderRequest request = PlaceOrderRequest.builder()
                .idempotencyKey("checkout-key")
                .build();

        AppException exception = assertThrows(
                AppException.class,
                () -> orderService.placeOrder(attacker, request));

        assertEquals(409, exception.getStatus().value());
        verify(orderMapper, never()).toResponse(any());
        verify(paymentRepository, never()).findByOrderId(any());
        verify(orderRepository, never()).save(any());
    }
}
