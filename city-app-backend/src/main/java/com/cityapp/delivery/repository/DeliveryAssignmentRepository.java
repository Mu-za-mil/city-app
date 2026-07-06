package com.cityapp.delivery.repository;

import com.cityapp.delivery.entity.DeliveryAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DeliveryAssignmentRepository
        extends JpaRepository<DeliveryAssignment, Long> {

    Optional<DeliveryAssignment> findByOrderId(Long orderId);
    Optional<DeliveryAssignment> findByOrderIdAndPartnerId(Long orderId, Long partnerId);

    // Partner's active deliveries (for partner dashboard)
    List<DeliveryAssignment> findByPartnerIdAndStatusOrderByAssignedAtDesc(
            Long partnerId,
            DeliveryAssignment.AssignmentStatus status);

    // Partner's earnings calculation base
    @Query("""
        SELECT COUNT(da) FROM DeliveryAssignment da
        WHERE da.partner.id = :partnerId
          AND da.status = 'DELIVERED'
        """)
    long countDeliveredByPartner(@Param("partnerId") Long partnerId);
}
