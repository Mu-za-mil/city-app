package com.cityapp.order.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cityapp.order.entity.Order;
import com.cityapp.order.entity.OrderStatus;

public interface OrderRepository extends JpaRepository<Order, Long> {

    // ── Core lookups ──────────────────────────────────────────────────────────

    Optional<Order> findByIdempotencyKey(String idempotencyKey);
    // Used for idempotency check: before creating, check if key already exists.

    Optional<Order> findByIdAndUserId(Long orderId, Long userId);
    // Ownership check: buyer can only see their own orders.

    /**
     * Find an order by id where the store's owner (seller) matches the given ownerId.
     * This is used when the authenticated principal is a seller (User) and we need
     * to verify they own the store for the order without requiring the client to
     * pass the store id.
     */
    Optional<Order> findByIdAndStoreOwnerId(Long orderId, Long ownerId);

    // ── List queries ──────────────────────────────────────────────────────────

    Page<Order> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    // Buyer's order history: newest first.

    Page<Order> findByStoreIdOrderByCreatedAtDesc(Long storeId, Pageable pageable);
    // Seller's order dashboard: all orders for a store.

    Page<Order> findByStoreIdAndStatusOrderByCreatedAtDesc(
            Long storeId, OrderStatus status, Pageable pageable);
    // Seller dashboard filtered by status: "show me PREPARING orders".

    // ── Saga timeout detection ────────────────────────────────────────────────

    /**
     * Find CREATED orders older than a cutoff time.
     * Used by SagaTimeoutHandler (Phase 10): if inventory deduction
     * hasn't happened within 2 minutes, assume Kafka failed, cancel the order.
     *
     * WHY A NATIVE QUERY:
     *   JPQL doesn't support complex date arithmetic as cleanly.
     *   Native SQL is more readable for this specific query.
     *   The partial index idx_orders_status_created (from V1 migration)
     *   makes this query fast: only scans CREATED orders, not all orders.
     */
    @Query(value = """ 
        SELECT * FROM orders 
        WHERE status = 'CREATED' 
          AND created_at < :cutoff 
        """, nativeQuery = true)
    List<Order> findCreatedOrdersOlderThan(@Param("cutoff") Instant cutoff);

    // ── Analytics ─────────────────────────────────────────────────────────────

    @Query(""" 
        SELECT COUNT(o) FROM Order o 
        WHERE o.store.id = :storeId 
          AND o.status NOT IN ('CANCELLED') 
          AND o.createdAt >= :since 
        """)
    long countOrdersSince(@Param("storeId") Long storeId,
                          @Param("since") Instant since);

    @Query(""" 
        SELECT COALESCE(SUM(o.totalAmount), 0) FROM Order o 
        WHERE o.store.id = :storeId 
          AND o.status = 'DELIVERED' 
          AND o.createdAt >= :since 
        """)
    java.math.BigDecimal sumRevenueDeliveredSince(
            @Param("storeId") Long storeId,
            @Param("since") Instant since);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
    select o
    from Order o
    where o.id = :id
      and o.user.id = :userId
""")
    Optional<Order> findByIdAndUserIdForUpdate(
            @Param("id") Long id,
            @Param("userId") Long userId);
}