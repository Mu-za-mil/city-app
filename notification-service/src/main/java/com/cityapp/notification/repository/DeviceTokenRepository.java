package com.cityapp.notification.repository;

import com.cityapp.notification.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DeviceTokenRepository
        extends JpaRepository<DeviceToken, Long> {

    List<DeviceToken> findByUserIdAndActiveTrue(Long userId);
    // Used for: "send push to all this user's devices"

    Optional<DeviceToken> findByToken(String token);
    // Used for: checking if a token is already registered

    @Modifying
    @Query("UPDATE DeviceToken dt SET dt.active = false WHERE dt.token = :token")
    void deactivateToken(@Param("token") String token);
    // Called when FCM returns UNREGISTERED for this token
}
