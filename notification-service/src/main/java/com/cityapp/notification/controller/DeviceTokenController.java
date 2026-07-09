package com.cityapp.notification.controller;

import com.cityapp.common.response.ApiResponse;
import com.cityapp.notification.entity.DeviceToken;
import com.cityapp.notification.repository.DeviceTokenRepository;
import com.cityapp.user.entity.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Manages FCM device token registration.
 *
 * WHEN TO REGISTER A TOKEN:
 *   Client-side: on every app launch (token may have rotated).
 *   The token should be sent immediately after login.
 *   If the app detects a new FCM token (Firebase calls onTokenRefresh):
 *   send the new token to this endpoint immediately.
 *
 * UPSERT SEMANTICS:
 *   If token already exists for this user: update platform (idempotent).
 *   If token is new: create a new record.
 *   UNIQUE constraint on token column enforces this at DB level.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/device-tokens")
@RequiredArgsConstructor
public class DeviceTokenController {

    private final DeviceTokenRepository deviceTokenRepository;

    @PostMapping
    public ResponseEntity<ApiResponse<Void>> registerToken(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody RegisterTokenRequest req) {

        // Upsert: find existing or create new
        deviceTokenRepository.findByToken(req.getToken())
                .ifPresentOrElse(
                        existing -> {
                            // Token exists: ensure it's active and associated with this user
                            existing.setActive(true);
                            deviceTokenRepository.save(existing);
                        },
                        () -> {
                            DeviceToken token = DeviceToken.builder()
                                    .user(user)
                                    .token(req.getToken())
                                    .platform(req.getPlatform())
                                    .active(true)
                                    .build();
                            deviceTokenRepository.save(token);
                            log.info("Device token registered: userId={} platform={}",
                                    user.getId(), req.getPlatform());
                        }
                );

        return ResponseEntity.ok(ApiResponse.ok("Device token registered"));
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse<Void>> unregisterToken(
            @RequestParam String token) {
        deviceTokenRepository.deactivateToken(token);
        return ResponseEntity.ok(ApiResponse.ok("Device token removed"));
    }

    // ── Request DTO ───────────────────────────────────────────────────────────

    @Getter
    @Setter
    public static class RegisterTokenRequest {
        @NotBlank(message = "FCM token is required")
        private String token;

        @NotBlank(message = "Platform is required")
        @Pattern(regexp = "IOS|ANDROID|WEB",
                message = "Platform must be IOS, ANDROID, or WEB")
        private String platform;
    }
}