package com.cityapp.order.saga;

import com.cityapp.common.constants.AppConstants;
import com.cityapp.common.event.RestoreStockCommand;
import com.cityapp.common.event.StockDeductedEvent;
import com.cityapp.order.entity.Order;
import com.cityapp.order.entity.OrderStatus;
import com.cityapp.order.repository.OrderRepository;
import com.cityapp.outbox.service.OutboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SagaReplyConsumerTest {

    @Mock OrderRepository orderRepository;
    @Mock OutboxService outboxService;

    @InjectMocks SagaReplyConsumer consumer;

    @Test
    void lateSuccessfulStockReply_shouldRequestCompensationForCancelledOrder() {
        Order order = mock(Order.class);
        when(order.getStatus()).thenReturn(OrderStatus.CANCELLED);
        when(order.getSagaId()).thenReturn("saga-1");
        when(order.getId()).thenReturn(20L);
        when(orderRepository.findById(20L)).thenReturn(Optional.of(order));

        consumer.onStockDeducted(StockDeductedEvent.builder()
                .sagaId("saga-1").orderId(20L).success(true).build());

        verify(outboxService).enqueue(
                eq(AppConstants.TOPIC_RESTORE_STOCK),
                eq("20"),
                any(RestoreStockCommand.class));
    }

    @Test
    void duplicateSuccessfulReplyForConfirmedOrder_shouldBeIgnored() {
        Order order = mock(Order.class);
        when(order.getStatus()).thenReturn(OrderStatus.CONFIRMED);
        when(orderRepository.findById(20L)).thenReturn(Optional.of(order));

        consumer.onStockDeducted(StockDeductedEvent.builder()
                .sagaId("saga-1").orderId(20L).success(true).build());

        verifyNoInteractions(outboxService);
    }
}