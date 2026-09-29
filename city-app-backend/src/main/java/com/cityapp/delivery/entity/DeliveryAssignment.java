package com.cityapp.delivery.entity;

import com.cityapp.order.entity.Order;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "delivery_assignments")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class DeliveryAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;
    // UNIQUE: one assignment per order. An order has exactly one delivery.

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partner_id", nullable = false)
    private DeliveryPartner partner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private AssignmentStatus status = AssignmentStatus.ASSIGNED;

    @CreationTimestamp
    @Column(name = "assigned_at", updatable = false)
    private Instant assignedAt;

    @Column(name = "picked_up_at")
    private Instant pickedUpAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "delivery_proof_url")
    private String deliveryProofUrl;
    // Photo proof of delivery — partner uploads after drop-off.
    // Stored in S3 (Phase 16). URL saved here.

    public enum AssignmentStatus {
        ASSIGNED,       // partner assigned, not yet picked up
        PICKED_UP,      // partner has the order, en route to buyer
        DELIVERED,      // delivered successfully
        CANCELLED       // assignment cancelled (partner unavailable, etc.)
    }
}