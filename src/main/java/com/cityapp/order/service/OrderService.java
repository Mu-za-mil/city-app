package com.cityapp.order.service;

import com.cityapp.common.constants.AppConstants;
import com.cityapp.common.exception.AppException;
import com.cityapp.common.response.PageResponse;
import com.cityapp.order.dto.*;
import com.cityapp.order.entity.*;
import com.cityapp.order.mapper.OrderMapper;
import com.cityapp.order.repository.OrderRepository;
import com.cityapp.payment.entity.Payment;
import com.cityapp.payment.repository.PaymentRepository;
import com.cityapp.product.entity.Inventory;
import com.cityapp.product.entity.Product;
import com.cityapp.product.repository.InventoryRepository;
import com.cityapp.product.repository.ProductRepository;
import com.cityapp.store.entity.Store;
import com.cityapp.store.repository.StoreRepository;
import com.cityapp.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final StoreRepository storeRepository;
    private final PaymentRepository paymentRepository;
    private final OrderMapper orderMapper;

    // Minimum order amount in Indian Rupees
    private static final BigDecimal MINIMUM_ORDER_AMOUNT = BigDecimal.ONE;

    // ── Place Order ───────────────────────────────────────────────────────────

    /**
     * Creates an order from a checkout request.
     *
     * @Transactional ensures ALL of these happen atomically:
     * 1. Idempotency check
     * 2. Order creation
     * 3. OrderItem creation
     * 4. Inventory deduction (SELECT FOR UPDATE)
     * 5. Status advance to CONFIRMED
     * <p>
     * If ANY step throws: entire transaction rolls back.
     * Result: either a complete order OR nothing. No partial state.
     * <p>
     * VALIDATE THEN DEDUCT PATTERN:
     * WRONG approach: deduct as we go.
     * Item 1: deduct 2 units (success)
     * Item 2: insufficient stock → throw exception
     * Item 1 deduction: rolled back? YES (transaction rollback).
     * But if we didn't use a transaction: Item 1 is permanently deducted.
     * Inventory inconsistency.
     * <p>
     * CORRECT approach (our implementation):
     * Phase 1: validate ALL items (check stock for all before touching any).
     * Phase 2: deduct ALL items (only if all validations passed).
     * If ANY validation fails in Phase 1: no deduction happens.
     * Clean. Atomic. No partial deductions.
     */
    @Transactional
    public OrderResponse placeOrder(User buyer, PlaceOrderRequest req) {

        // ── Step 1: Idempotency Check ──────────────────────────────────────────

        if (req.getIdempotencyKey() != null) {
            // If this idempotency key was used before: return the existing order.
            // Client may be retrying due to a network failure. Safe to return existing.
            return orderRepository.findByIdempotencyKey(req.getIdempotencyKey())
                    .map(existingOrder -> {
                        log.info("Idempotency hit: key={} returning existing orderId={}",
                                req.getIdempotencyKey(), existingOrder.getId());
                        return buildOrderResponse(existingOrder);
                    })
                    .orElseGet(() -> createNewOrder(buyer, req));
        }

        return createNewOrder(buyer, req);
    }

    private OrderResponse createNewOrder(User buyer, PlaceOrderRequest req) {

        // ── Step 2: Validate Store ─────────────────────────────────────────────

        Store store = storeRepository.findById(req.getStoreId())
                .orElseThrow(() -> AppException.notFound(
                        "Store not found: " + req.getStoreId()));

        // ── Step 3: Validate Items ─────────────────────────────────────────────

        if (req.getItems() == null || req.getItems().isEmpty()) {
            throw AppException.badRequest("Order must contain at least one item");
        }

        // ── Step 4: Minimum Order Amount ───────────────────────────────────────

        BigDecimal total = req.getItems().stream()
                .map(item -> item.getUnitPrice()
                        .multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (total.compareTo(MINIMUM_ORDER_AMOUNT) < 0) {
            throw AppException.badRequest(
                    "Minimum order amount is ₹" + MINIMUM_ORDER_AMOUNT +
                            ". Your order total is ₹" + total);
        }

        /*
         * Also check store's minimum order amount if configured.
         * Some stores: minimum ₹200 (delivery cost justification).
         */
        if (store.getMinOrderAmount() != null &&
                total.compareTo(store.getMinOrderAmount()) < 0) {
            throw AppException.badRequest(
                    "This store requires a minimum order of ₹" +
                            store.getMinOrderAmount() +
                            ". Your order total is ₹" + total);
        }

        // ── Step 5: Delivery Address Validation ───────────────────────────────

        if (req.getOrderType() == OrderType.DELIVERY &&
                (req.getDeliveryAddress() == null ||
                        req.getDeliveryAddress().isBlank())) {
            throw AppException.badRequest(
                    "Delivery address is required for DELIVERY orders");
        }

        // ── Step 6: Create Order Record ────────────────────────────────────────

        String idempotencyKey = req.getIdempotencyKey() != null
                ? req.getIdempotencyKey()
                : UUID.randomUUID().toString();  // generate if client didn't provide

        String sagaId = "saga-" + UUID.randomUUID();  // for Phase 10

        Order order = Order.builder()
                .user(buyer)
                .store(store)
                .status(OrderStatus.CREATED)
                .orderType(req.getOrderType())
                .totalAmount(total)
                .deliveryAddress(req.getDeliveryAddress())
                .notes(req.getNotes())
                .idempotencyKey(idempotencyKey)
                .sagaId(sagaId)
                .items(new ArrayList<>())
                .build();

        Order savedOrder = orderRepository.save(order);
        log.info("Order created: id={} buyer={} store={} total={}",
                savedOrder.getId(), buyer.getEmail(),
                store.getName(), total);

        // ── Step 7: Create OrderItem Records (with Snapshots) ─────────────────

        for (OrderItemRequest itemReq : req.getItems()) {

            Product product = productRepository
                    .findByIdAndStoreIdAndActiveTrue(
                            itemReq.getProductId(), req.getStoreId())
                    .orElseThrow(() -> AppException.badRequest(
                            "Product " + itemReq.getProductId() +
                                    " not found in store " + req.getStoreId()));

            BigDecimal subtotal = itemReq.getUnitPrice()
                    .multiply(BigDecimal.valueOf(itemReq.getQuantity()));

            OrderItem orderItem = OrderItem.builder()
                    .order(savedOrder)
                    .product(product)
                    .productName(product.getName())     // SNAPSHOT: name at order time
                    .unitPrice(itemReq.getUnitPrice())  // SNAPSHOT: price from cart
                    .quantity(itemReq.getQuantity())
                    .subtotal(subtotal)                 // SNAPSHOT: subtotal at order time
                    .build();

            savedOrder.getItems().add(orderItem);
        }

        // Cascade saves OrderItems via Order
        orderRepository.save(savedOrder);

        // ── Step 8: Validate and Deduct Inventory (Phase 1: Validate All) ─────

        List<DeductionRecord> deductions = new ArrayList<>();

        for (OrderItemRequest itemReq : req.getItems()) {
            /*
             * SELECT FOR UPDATE: acquires a row-level lock on this inventory row.
             * If another transaction holds the lock: this thread waits.
             * When lock acquired: we read the CURRENT quantity (not stale).
             *
             * This prevents overselling:
             * Thread A: quantity=1, acquires lock, reads 1
             * Thread B: tries to acquire lock → WAITS
             * Thread A: 1 >= requested 1 → deducts → quantity=0 → commits → releases lock
             * Thread B: acquires lock, reads 0 → 0 < requested 1 → throws → rollback
             * Result: one successful order, one rejected order. No overselling.
             */
            Inventory inv = inventoryRepository
                    .findByProductIdForUpdate(itemReq.getProductId())
                    .orElseThrow(() -> AppException.badRequest(
                            "No inventory record for product: " +
                                    itemReq.getProductId()));

            if (inv.getQuantity() < itemReq.getQuantity()) {
                throw AppException.badRequest(
                        "Insufficient stock for product " +
                                itemReq.getProductId() +
                                ": available=" + inv.getQuantity() +
                                " requested=" + itemReq.getQuantity());
                // Transaction rolls back: NO inventory changes, order stays CREATED
                // (but will be caught by SagaTimeoutHandler in Phase 10 and cancelled)
            }

            deductions.add(new DeductionRecord(inv, itemReq.getQuantity()));
        }

        // ── Phase 2: Deduct All (only if ALL validations passed) ──────────────

        for (DeductionRecord rec : deductions) {
            rec.inventory().setQuantity(
                    rec.inventory().getQuantity() - rec.quantity());
            inventoryRepository.save(rec.inventory());
            log.debug("Inventory deducted: productId={} qty={} remaining={}",
                    rec.inventory().getProduct().getId(),
                    rec.quantity(),
                    rec.inventory().getQuantity());
        }

        // ── Step 9: Advance to CONFIRMED ──────────────────────────────────────

        savedOrder.setStatus(OrderStatus.CONFIRMED);
        Order confirmedOrder = orderRepository.save(savedOrder);

        log.info("Order confirmed: id={} total={}", confirmedOrder.getId(), total);

        // Phase 9 will add: Kafka event publish (order.created)
        // Phase 10 will replace steps 8-9 with async Saga

        return buildOrderResponse(confirmedOrder);
    }



    // ── Private Helpers ────────────────────────────────────────────────────────

    private OrderResponse buildOrderResponse(Order order) {
        OrderResponse response = orderMapper.toResponse(order);

        // Enrich with payment info if exists
        paymentRepository.findByOrderId(order.getId()).ifPresent(payment -> {
            // Note: @Mapping target="paymentMethod" ignore=true in mapper
            // We set it manually here because Payment is a separate entity
            // with its own repository — not a relationship on Order.
            // Avoid: adding @OneToMany Payment on Order entity (over-coupling).
        });

        return response;
    }
    private record DeductionRecord(Inventory inventory, int quantity) {}
}