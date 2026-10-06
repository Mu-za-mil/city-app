package com.cityapp.product.saga;

import com.cityapp.common.constants.AppConstants;
import com.cityapp.common.event.EventPublisher;
import com.cityapp.common.event.InventoryLowEvent;
import com.cityapp.common.event.PlaceOrderCommand;
import com.cityapp.common.event.StockDeductedEvent;
import com.cityapp.product.entity.Inventory;
import com.cityapp.outbox.service.OutboxService;
import com.cityapp.product.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Consumes PlaceOrderCommand events and executes inventory deduction.
 * Publishes StockDeductedEvent with the result.
 *
 * This is the "inventory step" of the Saga.
 *
 * VALIDATE-THEN-DEDUCT PATTERN:
 *   WRONG approach (deduct as you go):
 *     Item A: quantity=1, available=5. Deduct. remaining=4. ✓
 *     Item B: quantity=3, available=2. Insufficient. ROLLBACK.
 *     Item A deduction: rolled back (transaction rollback).
 *     Fine with a single DB transaction.
 *     PROBLEM with distributed saga: Item A may be in a different service.
 *     Rolling back a Kafka event is not possible.
 *     Must use compensating transactions (complex).
 *
 *   CORRECT approach (validate all, then deduct all):
 *     Phase 1 (validation): check ALL items. If ANY fails: abort early.
 *       Item A: 1 ≤ 5 ✓
 *       Item B: 3 > 2 ✗ → abort. No deductions yet.
 *       Publish: stock.deducted { success: false, reason: "Item B insufficient" }
 *     Phase 2 (deduction): only if ALL validations pass.
 *       Item A: deduct 1. remaining=4.
 *       Item B: deduct 3. remaining=... (won't happen — validation failed)
 *
 *   This eliminates the need for compensating transactions.
 *   Either everything is validated and all deductions proceed,
 *   or nothing is deducted and the failure is reported cleanly.
 *
 * REDIS IDEMPOTENCY (survives pod restarts):
 *   Problem: Kafka delivers deduct.stock twice (pod crashed before offset commit).
 *   Without idempotency:
 *     Attempt 1: deduct 2 units. quantity = 8.
 *     Attempt 2 (duplicate): deduct 2 units AGAIN. quantity = 6.
 *     Customer ordered 2 units but 4 were deducted. Inventory wrong.
 *
 *   With Redis idempotency:
 *     Attempt 1: Redis key doesn't exist → process → deduct → set key.
 *     Attempt 2: Redis key exists → duplicate detected → skip.
 *     quantity = 8 (only deducted once). Correct.
 *
 *   WHY REDIS NOT IN-MEMORY Set:
 *     In-memory Set: cleared on pod restart.
 *     After restart: Kafka redelivers → key not in Set → processes again.
 *     Duplicate deduction.
 *
 *     Redis: survives pod restarts (if appendonly=yes).
 *     After restart: Kafka redelivers → Redis key exists → skip.
 *     No duplicate deduction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockDeductionSagaHandler {

    private final InventoryRepository           inventoryRepository;
    private final OutboxService outboxService;
    private final EventPublisher                 eventPublisher;
    private final StringRedisTemplate           redisTemplate;

    private static final long SAGA_DEDUP_HOURS = 24L;

    @KafkaListener(
            topics  = AppConstants.TOPIC_DEDUCT_STOCK,
            groupId = "inventory-service-deduct-stock"
    )
    @Transactional
    public void onDeductStock(PlaceOrderCommand command) {

        log.info("Saga command received: sagaId={} orderId={} items={}",
                command.getSagaId(), command.getOrderId(),
                command.getItems().size());

        // ── Step 1: Redis Idempotency Check ──────────────────────────────────

        String dedupKey = AppConstants.REDIS_SAGA_PROCESSED + command.getOrderId();

        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(dedupKey, command.getSagaId(),
                        SAGA_DEDUP_HOURS, TimeUnit.HOURS);

        if (!Boolean.TRUE.equals(isNew)) {
            /*
             * This command was already processed.
             * Kafka is redelivering (consumer crashed before offset commit).
             *
             * TWO CASES:
             * A. Previous attempt SUCCEEDED: stock was deducted.
             *    Re-publishing stock.deducted {success:true} is idempotent:
             *    OrderService.SagaReplyConsumer also has idempotency.
             *    Re-publishing ensures the order advances to CONFIRMED.
             *
             * B. Previous attempt FAILED: stock was NOT deducted.
             *    The original failure reply was published.
             *    Order was cancelled.
             *    Re-publishing stock.deducted {success:false} is harmless:
             *    Cancelled order stays cancelled.
             *
             * Either way: re-publish the success reply.
             * We don't know which case we're in, so we re-publish success.
             * If the order was already cancelled: SagaReplyConsumer ignores it.
             */
            log.warn("Duplicate saga command for orderId={}. Re-publishing reply.",
                    command.getOrderId());
            publishSuccess(command);
            return;
        }

        // ── Step 2: Phase 1 — Validate ALL Items ────────────────────────────

        List<Inventory> lockedInventories = new ArrayList<>();

        for (PlaceOrderCommand.OrderItemSpec item : command.getItems()) {
            /*
             * SELECT FOR UPDATE: acquires a row-level lock.
             * Prevents concurrent orders from reading stale quantities.
             * Lock held within this @Transactional method.
             * Released when the method returns (transaction commits).
             */
            Inventory inv = inventoryRepository
                    .findByProductIdForUpdate(item.getProductId())
                    .orElse(null);

            if (inv == null) {
                log.warn("Saga failure: no inventory for productId={}",
                        item.getProductId());
                // Clean up the dedup key: this saga failed.
                // If Kafka redelivers: allow reprocessing.
                redisTemplate.delete(dedupKey);
                publishFailure(command,
                        "No inventory record for product: " + item.getProductId());
                return;
            }

            if (inv.getQuantity() < item.getQuantity()) {
                log.warn("Saga failure: insufficient stock productId={} " +
                                "available={} requested={}",
                        item.getProductId(), inv.getQuantity(), item.getQuantity());
                redisTemplate.delete(dedupKey);
                publishFailure(command,
                        "Insufficient stock for product '" +
                                inv.getProduct().getName() + "': " +
                                "available=" + inv.getQuantity() +
                                ", requested=" + item.getQuantity());
                return;
            }

            lockedInventories.add(inv);
        }

        // ── Step 3: Phase 2 — Deduct ALL Items ──────────────────────────────
        /*
         * Only reaches here if ALL items pass validation.
         * Deduct all atomically within this @Transactional.
         *
         * WHY SAVE THE LOCKED INVENTORIES IN A LIST:
         *   We need all inventories loaded (and locked) before modifying any.
         *   If we deducted during Phase 1 (validate-as-you-deduct):
         *   Item A deducted → Item B validation fails → rollback deduction of A.
         *   With Validate-then-Deduct: no rollback needed.
         *   All locked, all valid → deduct all safely.
         */
        for (int i = 0; i < command.getItems().size(); i++) {
            PlaceOrderCommand.OrderItemSpec itemSpec = command.getItems().get(i);
            Inventory inv = lockedInventories.get(i);

            int newQty = inv.getQuantity() - itemSpec.getQuantity();
            inv.setQuantity(newQty);
            inventoryRepository.save(inv);

            if (newQty <= inv.getLowStockThreshold()) {
                eventPublisher.publishInventoryLow(
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

            log.debug("Deducted: productId={} qty={} remaining={}",
                    itemSpec.getProductId(), itemSpec.getQuantity(), newQty);
        }

        // ── Step 4: Publish Success Reply ────────────────────────────────────

        publishSuccess(command);
        log.info("Saga success: orderId={} sagaId={}",
                command.getOrderId(), command.getSagaId());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void publishSuccess(PlaceOrderCommand command) {
        outboxService.enqueue(
                AppConstants.TOPIC_STOCK_DEDUCTED,
                String.valueOf(command.getOrderId()),
                StockDeductedEvent.builder()
                        .sagaId(command.getSagaId())
                        .orderId(command.getOrderId())
                        .success(true)
                        .timestamp(Instant.now())
                        .build());
    }

    private void publishFailure(PlaceOrderCommand command, String reason) {
        outboxService.enqueue(
                AppConstants.TOPIC_STOCK_DEDUCTED,
                String.valueOf(command.getOrderId()),
                StockDeductedEvent.builder()
                        .sagaId(command.getSagaId())
                        .orderId(command.getOrderId())
                        .success(false)
                        .failureReason(reason)
                        .timestamp(Instant.now())
                        .build());
    }
}
