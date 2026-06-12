package com.cityapp.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.cityapp.common.event.EventPublisher;
import com.cityapp.common.event.OrderCreatedEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cityapp.common.exception.AppException;
import com.cityapp.common.response.PageResponse;
import com.cityapp.order.dto.OrderItemRequest;
import com.cityapp.order.dto.OrderResponse;
import com.cityapp.order.dto.PlaceOrderRequest;
import com.cityapp.order.dto.UpdateStatusRequest;
import com.cityapp.order.entity.Order;
import com.cityapp.order.entity.OrderItem;
import com.cityapp.order.entity.OrderStatus;
import com.cityapp.order.entity.OrderType;
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
    private final EventPublisher eventPublisher;

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

        List<OrderCreatedEvent.OrderItemInfo> itemInfos = confirmedOrder.getItems()
                .stream()
                .map(item -> OrderCreatedEvent.OrderItemInfo.builder()
                        .productId(item.getProduct().getId())
                        .productName(item.getProductName())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .build())
                .toList();

        eventPublisher.publishOrderCreated(
                OrderCreatedEvent.builder()
                        .eventId(EventPublisher.generateEventId())
                        .orderId(confirmedOrder.getId())
                        .userId(buyer.getId())
                        .sellerId(confirmedOrder.getStore().getOwner().getId())
                        .storeId(confirmedOrder.getStore().getId())
                        .storeName(confirmedOrder.getStore().getName())
                        .orderType(confirmedOrder.getOrderType())
                        .totalAmount(confirmedOrder.getTotalAmount())
                        .deliveryAddress(confirmedOrder.getDeliveryAddress())
                        .items(itemInfos)
                        .timestamp(Instant.now())
                        .build());

        // Phase 10 will replace steps 8-9 with async Saga

        return buildOrderResponse(confirmedOrder);
    }

    // ── Status Updates ────────────────────────────────────────────────────────

    @Transactional
    public OrderResponse updateStatus(Long orderId, Long sellerId,
                                      UpdateStatusRequest req) {

        // Ownership: seller can only update their store's orders
        Order order = orderRepository.findByIdAndStoreOwnerId(orderId, sellerId)
                .orElseThrow(() -> AppException.notFound(
                        "Order not found: " + orderId));

        // State machine validation
        if (!order.getStatus().canTransitionTo(req.getStatus())) {
            throw AppException.badRequest(
                    "Cannot transition from " + order.getStatus() +
                            " to " + req.getStatus() +
                            ". Invalid state machine transition.");
        }

        // Special rules for CANCELLED status
        if (req.getStatus() == OrderStatus.CANCELLED) {
            validateCancellation(order, req.getCancellationReason());
        }

        order.setStatus(req.getStatus());
        if (req.getCancellationReason() != null) {
            order.setCancellationReason(req.getCancellationReason());
        }

        Order saved = orderRepository.save(order);
        log.info("Order status updated: id={} status={}", orderId, req.getStatus());
        return buildOrderResponse(saved);
    }

    @Transactional
    public OrderResponse cancelOrder(Long orderId, Long buyerId, String reason) {

        // Buyers can only cancel their own orders
        Order order = orderRepository.findByIdAndUserId(orderId, buyerId)
                .orElseThrow(() -> AppException.notFound(
                        "Order not found: " + orderId));

        if (!order.getStatus().canTransitionTo(OrderStatus.CANCELLED)) {
            throw AppException.badRequest(
                    "Order in status '" + order.getStatus() +
                            "' cannot be cancelled. " +
                            "Contact support for assistance.");
        }

        validateCancellation(order, reason);

        order.setStatus(OrderStatus.CANCELLED);
        order.setCancellationReason(reason);

        Order saved = orderRepository.save(order);
        log.info("Order cancelled by buyer: id={} reason={}", orderId, reason);
        return buildOrderResponse(saved);
    }

    private void validateCancellation(Order order, String reason) {
        /*
         * BUSINESS RULE: Cannot cancel an order with a successful payment.
         *
         * WHY:
         *   Payment status = SUCCESS → Razorpay has captured money.
         *   Simply cancelling the order doesn't trigger a Razorpay refund.
         *   The money is still held by Razorpay.
         *   Seller never gets paid. Buyer thinks they're refunded. They're not.
         *   Financial discrepancy. Compliance issue.
         *
         *   Correct process:
         *   1. Admin initiates refund through Razorpay dashboard.
         *   2. Razorpay webhook fires: payment.refunded.
         *   3. System updates payment status to REFUNDED.
         *   4. System updates order status to CANCELLED.
         *
         *   For Phase 7: block cancellation of paid orders.
         *   Phase 12 (Notifications) will add: "your refund has been initiated" notification.
         */
        paymentRepository.findByOrderId(order.getId())
                .ifPresent(payment -> {
                    if (payment.getStatus() == Payment.PaymentStatus.SUCCESS) {
                        throw AppException.badRequest(
                                "Cannot cancel a paid order. " +
                                        "Please contact support to initiate a refund.");
                    }
                });
    }

    // ── Queries ────────────────────────────────────────────────────────────────

    /**
     * INTENTIONALLY NOT CACHED.
     *
     * WHY:
     *   Buyers check order status frequently: "has my order been prepared yet?"
     *   The status changes in real-time: CONFIRMED → PREPARING → READY → OUT_FOR_DELIVERY
     *   If cached: buyer sees CONFIRMED status for 5 minutes after seller marks PREPARING.
     *   "Why is my order still showing CONFIRMED? The seller said it's ready!"
     *
     *   Orders are LOW READ frequency (each user has few orders, checks status occasionally)
     *   but HIGH UPDATE frequency (status changes multiple times).
     *   CACHING RULE: Cache high-read, low-update data.
     *                 Don't cache low-read, high-update data.
     *   Orders: borderline. The status update frequency makes caching harmful.
     */
    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId, Long userId) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> AppException.notFound(
                        "Order not found: " + orderId));
        return buildOrderResponse(order);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> getMyOrders(Long userId, Pageable pageable) {
        return PageResponse.from(
                orderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                        .map(this::buildOrderResponse));
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> getStoreOrders(Long storeId,
                                                      OrderStatus status,
                                                      Pageable pageable) {
        Page<Order> page = status != null
                ? orderRepository.findByStoreIdAndStatusOrderByCreatedAtDesc(
                storeId, status, pageable)
                : orderRepository.findByStoreIdOrderByCreatedAtDesc(
                storeId, pageable);
        return PageResponse.from(page.map(this::buildOrderResponse));
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