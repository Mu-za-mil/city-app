package com.cityapp.product.repository;

import com.cityapp.product.entity.SagaStockDeduction;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SagaStockDeductionRepository
        extends JpaRepository<SagaStockDeduction, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SagaStockDeduction s where s.sagaId = :sagaId")
    Optional<SagaStockDeduction> findBySagaIdForUpdate(@Param("sagaId") String sagaId);
}