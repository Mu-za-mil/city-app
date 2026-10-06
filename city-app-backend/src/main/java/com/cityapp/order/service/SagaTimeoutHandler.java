package com.cityapp.order.service;

import com.cityapp.common.constants.AppConstants;
import com.cityapp.common.event.EventPublisher;
import com.cityapp.common.event.OrderStatusChangedEvent;
import com.cityapp.common.event.RestoreStockCommand;
import com.cityapp.order.entity.Order;
import com.cityapp.order.entity.OrderStatus;
import com.cityapp.order.repository.OrderRepository;
import com.cityapp.outbox.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SagaTimeoutHandler {
    private final OrderRepository orderRepository;
    private final OutboxService outboxService;
    private static final long TIMEOUT_MINUTES = 2L;

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void cancelTimedOutOrders() {
        Instant cutoff = Instant.now().minus(TIMEOUT_MINUTES, ChronoUnit.MINUTES);
        List<Order> stuckOrders = orderRepository.findCreatedOrdersOlderThan(cutoff);

        for (Order order : stuckOrders) {
            if (order.getStatus() != OrderStatus.CREATED) continue;

            order.setStatus(OrderStatus.CANCELLED);
            order.setCancellationReason(
                    "Order automatically cancelled: inventory deduction did not complete within "
                            + TIMEOUT_MINUTES + " minutes. Please try again.");
            orderRepository.save(order);

            if (order.getSagaId() != null) {
                outboxService.enqueue(
                        AppConstants.TOPIC_RESTORE_STOCK,
                        String.valueOf(order.getId()),
                        RestoreStockCommand.builder()
                                .sagaId(order.getSagaId())
                                .orderId(order.getId())
                                .timestamp(Instant.now())
                                .build());
            }

            outboxService.enqueue(
                    AppConstants.TOPIC_ORDER_STATUS_CHANGED,
                    String.valueOf(order.getId()),
                    OrderStatusChangedEvent.builder()
                            .eventId(EventPublisher.generateEventId())
                            .orderId(order.getId())
                            .userId(order.getUser().getId())
                            .sellerId(order.getStore().getOwner().getId())
                            .previousStatus(OrderStatus.CREATED)
                            .newStatus(OrderStatus.CANCELLED)
                            .cancellationReason(order.getCancellationReason())
                            .timestamp(Instant.now())
                            .build());
        }
    }
}