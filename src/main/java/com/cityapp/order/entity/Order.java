package com.cityapp.order.entity;

import com.cityapp.store.entity.Store;
import com.cityapp.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Who placed it, where from ─────────────────────────────────────────────
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    // ── Idempotency ───────────────────────────────────────────────────────────
    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;
    /*
     * WHY IDEMPOTENCY KEY:
     *   Client sends: POST /orders (checkout)
     *   Server creates order. Takes 800ms.
     *   Network drops BEFORE server response reaches client.
     *   Client: "Did it work?" → retries the same request.
     *
     *   WITHOUT idempotency key:
     *   Second request: creates a second order.
     *   Buyer is charged twice. Inventory deducted twice.
     *   Financial error. Support ticket. Refund needed.
     *
     *   WITH idempotency key:
     *   Client generates a UUID: "checkout-session-abc123" (once per checkout attempt).
     *   First request: order created, idempotency_key = "checkout-session-abc123".
     *   Second request (retry): find order by idempotency_key → order found → return it.
     *   No new order created. No duplicate charge. No inventory double-deduction.
     *
     *   The UNIQUE constraint on idempotency_key is the DB-level guarantee.
     *   Even if the service check is bypassed: DB rejects the duplicate INSERT.
     */

    // ── Status ────────────────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private OrderStatus status = OrderStatus.CREATED;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false)
    private OrderType orderType;

    // ── Financials ────────────────────────────────────────────────────────────
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;
    /*
     * WHY STORE TOTAL_AMOUNT:
     *   Could compute: SUM(order_items.subtotal).
     *   Problem: products are soft-deleted, prices change.
     *   Snapshot: total_amount at order time is immutable.
     *   Historical orders always show the correct total.
     *   Also: avoids re-computing on every order fetch.
     */

    // ── Delivery details ──────────────────────────────────────────────────────
    @Column(name = "delivery_address")
    private String deliveryAddress;

    @Column(name = "delivery_lat")
    private Double deliveryLat;

    @Column(name = "delivery_lng")
    private Double deliveryLng;

    // ── Metadata ──────────────────────────────────────────────────────────────
    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Column(name = "saga_id")
    private String sagaId;
    // sagaId: correlation ID for the distributed Saga (Phase 10).
    // Links the deduct.stock command to this order.

    private String notes;

    // ── Line Items ────────────────────────────────────────────────────────────
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();
    /*
     * cascade = CascadeType.ALL: when we save the Order, all OrderItems are saved.
     * No need to save each OrderItem separately.
     * orphanRemoval = true: if an OrderItem is removed from the list, it's deleted from DB.
     *
     * For our use case: we NEVER modify order items after creation.
     * Orders are immutable once created. This cascade is a convenience only.
     */

    // ── Timestamps ────────────────────────────────────────────────────────────
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
