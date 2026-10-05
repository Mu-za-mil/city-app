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

    @Query("""
        SELECT da FROM DeliveryAssignment da
        JOIN FETCH da.order o
        JOIN FETCH o.user buyer
        JOIN FETCH o.store store
        JOIN FETCH store.owner seller
        JOIN FETCH da.partner partner
        JOIN FETCH partner.user partnerUser
        WHERE o.id = :orderId
          AND (
              buyer.id = :userId
              OR partnerUser.id = :userId
              OR seller.id = :userId
              OR :userId IN (
                  SELECT admin.id FROM User admin WHERE admin.id = :userId AND admin.role = com.cityapp.user.entity.Role.SUPER_ADMIN
              )
          )
        """)
    Optional<DeliveryAssignment> findAuthorizedByOrderId(
            @Param("orderId") Long orderId,
            @Param("userId") Long userId);

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
