package com.cityapp.order.service;

import com.cityapp.common.constants.AppConstants;
import com.cityapp.common.event.OrderStatusChangedEvent;
import com.cityapp.common.event.RestoreStockCommand;
import com.cityapp.order.entity.Order;
import com.cityapp.order.entity.OrderStatus;
import com.cityapp.order.repository.OrderRepository;
import com.cityapp.outbox.service.OutboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SagaTimeoutHandlerTest {

    @Mock OrderRepository orderRepository;
    @Mock OutboxService outboxService;

    @Test
    void timedOutOrder_shouldCancelAndEnqueueCompensation() {
        Order order = mock(Order.class);
        when(order.getStatus()).thenReturn(OrderStatus.CREATED);
        when(order.getSagaId()).thenReturn("saga-1");
        when(order.getId()).thenReturn(20L);
        when(order.getUser()).thenReturn(mock(com.cityapp.user.entity.User.class));
        when(order.getStore()).thenReturn(mock(com.cityapp.store.entity.Store.class));

        SagaTimeoutHandler handler = new SagaTimeoutHandler(orderRepository, outboxService);
        when(orderRepository.findCreatedOrdersOlderThan(any())).thenReturn(List.of(order));

        handler.cancelTimedOutOrders();

        verify(order).setStatus(OrderStatus.CANCELLED);
        verify(orderRepository).save(order);
        verify(outboxService).enqueue(
                eq(AppConstants.TOPIC_RESTORE_STOCK),
                eq("20"),
                any(RestoreStockCommand.class));
        verify(outboxService).enqueue(
                eq(AppConstants.TOPIC_ORDER_STATUS_CHANGED),
                eq("20"),
                any(OrderStatusChangedEvent.class));
    }
}