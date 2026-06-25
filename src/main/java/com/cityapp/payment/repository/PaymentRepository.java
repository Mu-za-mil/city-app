package com.cityapp.payment.repository;

import com.cityapp.payment.entity.Payment;
import com.cityapp.payment.entity.Payment.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findAllByOrderId(Long orderId);

    Optional<Payment> findTopByOrderIdOrderByCreatedAtDesc(Long orderId);

    Optional<Payment> findTopByOrderIdAndStatusOrderByCreatedAtDesc(
            Long orderId,
            PaymentStatus status
    );

    boolean existsByOrderIdAndStatus(
            Long orderId,
            PaymentStatus status
    );

    Optional<Payment> findByGatewayOrderId(String gatewayOrderId);

    Optional<Payment> findByGatewayPaymentId(String gatewayPaymentId);
}