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
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockDeductionSagaHandler {

    private final InventoryRepository inventoryRepository;
    private final SagaStockDeductionRepository deductionRepository;
    private final OutboxService outboxService;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;


    @KafkaListener(topics = AppConstants.TOPIC_DEDUCT_STOCK,
            groupId = "inventory-service-deduct-stock")
    @Transactional
    public void onDeductStock(PlaceOrderCommand command) {

        // PostgreSQL is the source of truth for Saga idempotency.
        // Redis cannot participate atomically in this DB transaction.
        SagaStockDeduction existing = deductionRepository
                .findBySagaIdForUpdate(command.getSagaId())
                .orElse(null);

        if (existing != null) {
            if (existing.getStatus() == SagaStockDeduction.Status.DEDUCTED) {
                publishSuccess(command);
            }
            // RESTORED means this Saga was already compensated. Never deduct again.
            return;
        }

        // Always acquire inventory locks in the same order across transactions.
        // Without this, Order A can lock product 10 then 20 while Order B locks
        // product 20 then 10, creating a classic database deadlock.
        List<PlaceOrderCommand.OrderItemSpec> sortedItems = command.getItems().stream()
                .sorted(Comparator.comparing(PlaceOrderCommand.OrderItemSpec::getProductId))
                .toList();

        List<Inventory> lockedInventories = new ArrayList<>();
        for (PlaceOrderCommand.OrderItemSpec item : sortedItems) {
            Inventory inv = inventoryRepository.findByProductIdForUpdate(item.getProductId()).orElse(null);
            if (inv == null) {
                publishFailure(command, "No inventory record for product: " + item.getProductId());
                return;
            }
            if (inv.getQuantity() < item.getQuantity()) {
                publishFailure(command,
                        "Insufficient stock for product '" + inv.getProduct().getName()
                                + "': available=" + inv.getQuantity()
                                + ", requested=" + item.getQuantity());
                return;
            }
            lockedInventories.add(inv);
        }

        // A concurrent delivery of the same Saga may have waited on the inventory
        // lock. Re-check after acquiring it so it cannot deduct twice.
        existing = deductionRepository
                .findBySagaIdForUpdate(command.getSagaId())
                .orElse(null);

        if (existing != null) {
            if (existing.getStatus() == SagaStockDeduction.Status.DEDUCTED) {
                publishSuccess(command);
            }
            return;
        }

        for (int i = 0; i < sortedItems.size(); i++) {
            var spec = sortedItems.get(i);
            Inventory inv = lockedInventories.get(i);
            int newQty = inv.getQuantity() - spec.getQuantity();
            inv.setQuantity(newQty);
            inventoryRepository.save(inv);

            if (newQty <= inv.getLowStockThreshold()) {
                outboxService.enqueue(
                        AppConstants.TOPIC_INVENTORY_LOW,
                        String.valueOf(inv.getProduct().getId()),
                        InventoryLowEvent.builder()
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

            // Compensation must use the same global lock order as deduction.
            items = items.stream()
                    .sorted(Comparator.comparing(PlaceOrderCommand.OrderItemSpec::getProductId))
                    .toList();

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