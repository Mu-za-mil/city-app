package com.cityapp.notification.entity;

import com.cityapp.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;

@Entity
@Table(name = "device_tokens")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class DeviceToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, unique = true, length = 500)
    private String token;
    // FCM registration token from the device.
    // UNIQUE: same token cannot be registered twice (prevents duplicates).
    // A single user may have multiple tokens (phone + tablet + web).

    @Column(nullable = false, length = 10)
    private String platform;   // "IOS", "ANDROID", "WEB"

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
    // Tokens expire when: user uninstalls app, reinstalls, FCM rotates the token.
    // FCM returns UNREGISTERED error when a token is invalid.
    // We set active=false on UNREGISTERED error, not delete (audit trail).

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}