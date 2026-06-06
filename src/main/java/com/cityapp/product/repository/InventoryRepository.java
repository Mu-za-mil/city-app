package com.cityapp.product.repository;

import com.cityapp.product.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByProductId(Long productId);

    // Batch fetch: used in Phase 10 (Saga) and SearchService (N+1 fix)
    List<Inventory> findByProductIdIn(List<Long> productIds);

    /**
     * SELECT ... FOR UPDATE: acquires a pessimistic write lock.
     *
     * WHY PESSIMISTIC LOCKING FOR INVENTORY DEDUCTION:
     *
     *   Scenario: Two buyers simultaneously order the last unit.
     *   Both see quantity=1.
     *   Both proceed to deduct.
     *   Both update: quantity = 1 - 1 = 0.
     *   DB has two UPDATE statements. One succeeds. One... also succeeds?
     *   Actually: without locking, both read 1, both compute 0, both write 0.
     *   Inventory = 0 but TWO orders placed. Overselling.
     *
     *   WITH SELECT FOR UPDATE:
     *   Thread A: SELECT ... FOR UPDATE → acquires lock on row
     *   Thread B: SELECT ... FOR UPDATE → WAITS (row is locked)
     *   Thread A: quantity = 1 - 1 = 0. UPDATE. COMMIT. Lock released.
     *   Thread B: Lock acquired. quantity = 0. 0 < requested 1. FAIL.
     *
     *   Thread B gets StockInsufficientException instead of overselling.
     *
     *   This is the database enforcing mutual exclusion on inventory deduction.
     *   The @Retryable in InventoryService handles PessimisticLockingFailureException
     *   (when the wait timeout is exceeded — very contended inventory).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inventory i WHERE i.product.id = :productId")
    Optional<Inventory> findByProductIdForUpdate(@Param("productId") Long productId);
}
