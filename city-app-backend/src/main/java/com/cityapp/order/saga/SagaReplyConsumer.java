package com.cityapp.order.saga;

import com.cityapp.common.constants.AppConstants;
import com.cityapp.common.event.EventPublisher;
import com.cityapp.common.event.OrderCreatedEvent;
import com.cityapp.common.event.OrderStatusChangedEvent;
import com.cityapp.common.event.RestoreStockCommand;
import com.cityapp.common.event.StockDeductedEvent;
import com.cityapp.order.entity.Order;
import com.cityapp.order.entity.OrderStatus;
import com.cityapp.order.repository.OrderRepository;
import com.cityapp.outbox.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SagaReplyConsumer {
    private final OrderRepository orderRepository;
    private final OutboxService outboxService;

    @KafkaListener(topics = AppConstants.TOPIC_STOCK_DEDUCTED,
            groupId = "order-service-stock-deducted")
    @Transactional
    public void onStockDeducted(@Payload StockDeductedEvent event) {
        Order order = orderRepository.findById(event.getOrderId()).orElse(null);
        if (order == null) return;

        if (order.getStatus() != OrderStatus.CREATED) {
            if (event.isSuccess() && order.getStatus() == OrderStatus.CANCELLED
                    && order.getSagaId() != null) {
                outboxService.enqueue(
                        AppConstants.TOPIC_RESTORE_STOCK,
                        String.valueOf(order.getId()),
                        RestoreStockCommand.builder()
                                .sagaId(order.getSagaId())
                                .orderId(order.getId())
                                .timestamp(Instant.now())
                                .build());
            }
            return;
        }

        if (event.isSuccess()) {
            handleSuccess(order, event);
        } else {
            handleFailure(order, event);
        }
    }

    private void handleSuccess(Order order, StockDeductedEvent event) {
        order.setStatus(OrderStatus.CONFIRMED);
        orderRepository.save(order);

        List<OrderCreatedEvent.OrderItemInfo> itemInfos = order.getItems().stream()
                .map(item -> OrderCreatedEvent.OrderItemInfo.builder()
                        .productId(item.getProduct().getId())
                        .productName(item.getProductName())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .build()).toList();

        outboxService.enqueue(AppConstants.TOPIC_ORDER_CREATED,
                String.valueOf(order.getId()),
                OrderCreatedEvent.builder()
                        .eventId(EventPublisher.generateEventId())
                        .orderId(order.getId()).userId(order.getUser().getId())
                        .sellerId(order.getStore().getOwner().getId())
                        .storeId(order.getStore().getId()).storeName(order.getStore().getName())
                        .orderType(order.getOrderType()).totalAmount(order.getTotalAmount())
                        .deliveryAddress(order.getDeliveryAddress()).items(itemInfos)
                        .timestamp(Instant.now()).build());
    }

    private void handleFailure(Order order, StockDeductedEvent event) {
        order.setStatus(OrderStatus.CANCELLED);
        order.setCancellationReason("Order automatically cancelled: " + event.getFailureReason());
        orderRepository.save(order);

        outboxService.enqueue(AppConstants.TOPIC_ORDER_STATUS_CHANGED,
                String.valueOf(order.getId()),
                OrderStatusChangedEvent.builder()
                        .eventId(EventPublisher.generateEventId())
                        .orderId(order.getId()).userId(order.getUser().getId())
                        .sellerId(order.getStore().getOwner().getId())
                        .previousStatus(OrderStatus.CREATED).newStatus(OrderStatus.CANCELLED)
                        .cancellationReason(order.getCancellationReason())
                        .timestamp(Instant.now()).build());
    }
}