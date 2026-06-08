package com.cityapp.order.entity;

import com.cityapp.common.enums.OrderStatus;
import com.cityapp.common.enums.OrderType;
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
@Table(name = "orders",
       indexes = {
           @Index(name = "idx_orders_user",   columnList = "user_id"),
           @Index(name = "idx_orders_store",  columnList = "store_id"),
           @Index(name = "idx_orders_status", columnList = "status"),
           @Index(name = "idx_orders_type",   columnList = "order_type")
       })
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String idempotencyKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false)
    @Builder.Default
    private OrderType orderType = OrderType.DELIVERY;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private OrderStatus status = OrderStatus.CREATED;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    /**
     * Required for DELIVERY, null for TAKEAWAY.
     * Validation is enforced in the service layer, not here.
     */
    @Column(columnDefinition = "TEXT")
    private String deliveryAddress;

    private String notes;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    @CreationTimestamp @Column(updatable = false) private Instant createdAt;
    @UpdateTimestamp private Instant updatedAt;
}
