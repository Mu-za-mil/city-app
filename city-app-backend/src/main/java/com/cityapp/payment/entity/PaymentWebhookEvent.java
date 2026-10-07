package com.cityapp.payment.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "payment_webhook_events")
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class PaymentWebhookEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_id", nullable = false, unique = true, length = 100)
    private String eventId;
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;
    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;
}
