package com.cityapp.product.saga;

import com.cityapp.common.constants.AppConstants;
import com.cityapp.common.event.EventPublisher;
import com.cityapp.common.event.InventoryLowEvent;
import com.cityapp.common.event.PlaceOrderCommand;
import com.cityapp.common.event.RestoreStockCommand;
import com.cityapp.common.event.StockDeductedEvent;
import com.cityapp.common.event.StockRestoredEvent;
import com.cityapp.outbox.service.OutboxService;
import com.cityapp.product.entity.Inventory;
import com.cityapp.product.entity.SagaStockDeduction;
import com.cityapp.product.repository.InventoryRepository;
import com.cityapp.product.repository.SagaStockDeductionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockDeductionSagaHandler {

    private final InventoryRepository inventoryRepository;
    private final SagaStockDeductionRepository deductionRepository;
    private final OutboxService outboxService;
    private final EventPublisher eventPublisher;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final long SAGA_DEDUP_HOURS = 24L;

    @KafkaListener(topics = AppConstants.TOPIC_DEDUCT_STOCK,
            groupId = "inventory-service-deduct-stock")
    @Transactional
    public void onDeductStock(PlaceOrderCommand command) {
        String dedupKey = AppConstants.REDIS_SAGA_PROCESSED + command.getOrderId();
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(dedupKey, command.getSagaId(), SAGA_DEDUP_HOURS, TimeUnit.HOURS);

        if (!Boolean.TRUE.equals(isNew)) {
            SagaStockDeduction deduction = deductionRepository
                    .findBySagaIdForUpdate(command.getSagaId()).orElse(null);
            if (deduction != null && deduction.getStatus() == SagaStockDeduction.Status.DEDUCTED) {
                publishSuccess(command);
            }
            return;
        }

        List<Inventory> lockedInventories = new ArrayList<>();
        for (PlaceOrderCommand.OrderItemSpec item : command.getItems()) {
            Inventory inv = inventoryRepository.findByProductIdForUpdate(item.getProductId()).orElse(null);
            if (inv == null) {
                redisTemplate.delete(dedupKey);
                publishFailure(command, "No inventory record for product: " + item.getProductId());
                return;
            }
            if (inv.getQuantity() < item.getQuantity()) {
                redisTemplate.delete(dedupKey);
                publishFailure(command,
                        "Insufficient stock for product '" + inv.getProduct().getName()
                                + "': available=" + inv.getQuantity()
                                + ", requested=" + item.getQuantity());
                return;
            }
            lockedInventories.add(inv);
        }

        for (int i = 0; i < command.getItems().size(); i++) {
            var spec = command.getItems().get(i);
            Inventory inv = lockedInventories.get(i);
            int newQty = inv.getQuantity() - spec.getQuantity();
            inv.setQuantity(newQty);
            inventoryRepository.save(inv);

            if (newQty <= inv.getLowStockThreshold()) {
                eventPublisher.publishInventoryLow(InventoryLowEvent.builder()
                        .eventId(EventPublisher.generateEventId())
                        .productId(inv.getProduct().getId())
                        .productName(inv.getProduct().getName())
                        .storeId(inv.getProduct().getStore().getId())
                        .sellerId(inv.getProduct().getStore().getOwner().getId())
                        .currentQuantity(newQty)
                        .threshold(inv.getLowStockThreshold())
                        .timestamp(Instant.now())
                        .build());
            }
        }

        try {
            deductionRepository.save(SagaStockDeduction.builder()
                    .sagaId(command.getSagaId())
                    .orderId(command.getOrderId())
                    .itemsJson(objectMapper.writeValueAsString(command.getItems()))
                    .status(SagaStockDeduction.Status.DEDUCTED)
                    .build());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to persist saga stock deduction", e);
        }

        publishSuccess(command);
    }

    @KafkaListener(topics = AppConstants.TOPIC_RESTORE_STOCK,
            groupId = "inventory-service-restore-stock")
    @Transactional
    public void onRestoreStock(RestoreStockCommand command) {
        SagaStockDeduction deduction = deductionRepository
                .findBySagaIdForUpdate(command.getSagaId()).orElse(null);

        if (deduction == null) {
            publishRestoreResult(command, false, "No recorded stock deduction for saga");
            return;
        }

        if (deduction.getStatus() == SagaStockDeduction.Status.RESTORED) {
            publishRestoreResult(command, true, "Stock already restored");
            return;
        }

        try {
            List<PlaceOrderCommand.OrderItemSpec> items =
                    objectMapper.readValue(deduction.getItemsJson(),
                            objectMapper.getTypeFactory().constructCollectionType(
                                    List.class, PlaceOrderCommand.OrderItemSpec.class));

            for (PlaceOrderCommand.OrderItemSpec item : items) {
                Inventory inv = inventoryRepository.findByProductIdForUpdate(item.getProductId())
                        .orElseThrow(() -> new IllegalStateException(
                                "No inventory record for product: " + item.getProductId()));
                inv.setQuantity(inv.getQuantity() + item.getQuantity());
                inventoryRepository.save(inv);
            }

            deduction.setStatus(SagaStockDeduction.Status.RESTORED);
            deduction.setCompensatedAt(Instant.now());
            deductionRepository.save(deduction);
            publishRestoreResult(command, true, null);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize saga stock deduction", e);
        }
    }

    private void publishSuccess(PlaceOrderCommand command) {
        outboxService.enqueue(AppConstants.TOPIC_STOCK_DEDUCTED,
                String.valueOf(command.getOrderId()),
                StockDeductedEvent.builder()
                        .sagaId(command.getSagaId()).orderId(command.getOrderId())
                        .success(true).timestamp(Instant.now()).build());
    }

    private void publishFailure(PlaceOrderCommand command, String reason) {
        outboxService.enqueue(AppConstants.TOPIC_STOCK_DEDUCTED,
                String.valueOf(command.getOrderId()),
                StockDeductedEvent.builder()
                        .sagaId(command.getSagaId()).orderId(command.getOrderId())
                        .success(false).failureReason(reason).timestamp(Instant.now()).build());
    }

    private void publishRestoreResult(RestoreStockCommand command, boolean restored, String reason) {
        outboxService.enqueue(AppConstants.TOPIC_STOCK_RESTORED,
                String.valueOf(command.getOrderId()),
                StockRestoredEvent.builder()
                        .sagaId(command.getSagaId()).orderId(command.getOrderId())
                        .restored(restored).reason(reason).timestamp(Instant.now()).build());
    }
}