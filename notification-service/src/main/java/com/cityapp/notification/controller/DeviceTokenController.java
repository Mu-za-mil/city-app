package com.cityapp.notification.controller;

import com.cityapp.common.exception.AppException;
import com.cityapp.common.response.ApiResponse;
import com.cityapp.notification.entity.DeviceToken;
import com.cityapp.notification.repository.DeviceTokenRepository;
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

        deviceTokenRepository.findByToken(req.getToken())
                .ifPresentOrElse(
                        existing -> {
                            if (!existing.getUserId().equals(toDatabaseUserId(userId))) {
                                throw AppException.forbidden(
                                        "Device token belongs to another user");
                            }

                            existing.setActive(true);
                            existing.setPlatform(req.getPlatform());
                            deviceTokenRepository.save(existing);
                        },
                        () -> {
                            DeviceToken token = DeviceToken.builder()
                                    .userId(toDatabaseUserId(userId))
                                    .token(req.getToken())
                                    .platform(req.getPlatform())
                                    .active(true)
                                    .build();
                            deviceTokenRepository.save(token);

                            log.info("Device token registered: userId={} platform={}",
                                    userId, req.getPlatform());
                        }
                );

        return ResponseEntity.ok(ApiResponse.ok("Device token registered"));
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse<Void>> unregisterToken(
            @AuthenticationPrincipal User user,
            @RequestParam(name = "token") String token) {

        DeviceToken existing = deviceTokenRepository.findByToken(token)
                .orElseThrow(() -> AppException.notFound("Device token not found"));

        if (!existing.getUserId().equals(toDatabaseUserId(userId))) {
            throw AppException.forbidden("Cannot remove another user's device token");
        }

        deviceTokenRepository.deactivateToken(token);

        return ResponseEntity.ok(ApiResponse.ok("Device token removed"));
    }

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
