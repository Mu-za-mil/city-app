package com.cityapp.order.service;

import com.cityapp.common.event.EventPublisher;
import com.cityapp.common.event.OrderStatusChangedEvent;
import com.cityapp.order.dto.OrderResponse;
import com.cityapp.order.dto.UpdateStatusRequest;
import com.cityapp.order.entity.Order;
import com.cityapp.order.entity.OrderStatus;
import com.cityapp.order.mapper.OrderMapper;
import com.cityapp.order.repository.OrderRepository;
import com.cityapp.payment.repository.PaymentRepository;
import com.cityapp.product.repository.InventoryRepository;
import com.cityapp.product.repository.ProductRepository;
import com.cityapp.store.entity.Store;
import com.cityapp.store.repository.StoreRepository;
import com.cityapp.user.entity.User;
import com.cityapp.outbox.service.OutboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderStatusEventTest {

    @Mock private OrderRepository orderRepository;
    @Mock private ProductRepository productRepository;
    @Mock private InventoryRepository inventoryRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private OrderMapper orderMapper;
    @Mock private EventPublisher eventPublisher;
    @Mock private OutboxService outboxService;

    @InjectMocks private OrderService orderService;

    @Test
    void updateStatus_shouldPublishActualPreviousAndNewStatuses() {
        User buyer = user(1L);
        User seller = user(2L);
        Store store = Store.builder().id(20L).owner(seller).name("Test Store").build();
        Order order = Order.builder()
                .id(100L).user(buyer).store(store).status(OrderStatus.CONFIRMED).build();
        UpdateStatusRequest request = new UpdateStatusRequest();
        request.setStatus(OrderStatus.PREPARING);

        when(orderRepository.findByIdAndStoreOwnerId(100L, 2L))
                .thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderMapper.toResponse(order)).thenReturn(OrderResponse.builder().id(100L).build());
        when(paymentRepository.findByOrderId(100L)).thenReturn(Optional.empty());

        orderService.updateStatus(100L, 2L, request);

        ArgumentCaptor<OrderStatusChangedEvent> captor =
                ArgumentCaptor.forClass(OrderStatusChangedEvent.class);
        verify(eventPublisher).publishOrderStatusChanged(captor.capture());
        assertEquals(OrderStatus.CONFIRMED, captor.getValue().getPreviousStatus());
        assertEquals(OrderStatus.PREPARING, captor.getValue().getNewStatus());
        assertEquals(100L, captor.getValue().getOrderId());
    }

    @Test
    void cancelOrder_shouldPublishStatusChangedEventForBuyerCancellation() {
        User buyer = user(1L);
        User seller = user(2L);
        Store store = Store.builder().id(20L).owner(seller).name("Test Store").build();
        Order order = Order.builder()
                .id(100L).user(buyer).store(store).status(OrderStatus.CONFIRMED).build();

        when(orderRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderId(100L)).thenReturn(Optional.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderMapper.toResponse(order)).thenReturn(OrderResponse.builder().id(100L).build());

        orderService.cancelOrder(100L, 1L, "Changed my mind");

        ArgumentCaptor<OrderStatusChangedEvent> captor =
                ArgumentCaptor.forClass(OrderStatusChangedEvent.class);
        verify(eventPublisher).publishOrderStatusChanged(captor.capture());
        assertEquals(OrderStatus.CONFIRMED, captor.getValue().getPreviousStatus());
        assertEquals(OrderStatus.CANCELLED, captor.getValue().getNewStatus());
        assertEquals("Changed my mind", captor.getValue().getCancellationReason());
    }

    private static User user(Long id) {
        return User.builder()
                .id(id)
                .name("Test User")
                .email("user" + id + "@example.com")
                .passwordHash("hash")
                .build();
    }
}
