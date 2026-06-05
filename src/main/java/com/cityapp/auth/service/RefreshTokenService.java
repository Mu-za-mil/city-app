package com.cityapp.auth.service;

import com.cityapp.auth.entity.RefreshToken;
import com.cityapp.auth.repository.RefreshTokenRepository;
import com.cityapp.common.constants.AppConstants;
import com.cityapp.common.exception.AppException;
import com.cityapp.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${cityapp.jwt.refresh-expiration-days:30}")
    private long refreshExpirationDays;

    // ── Create ─────────────────────────────────────────────────────────────────

    @Transactional
    public RefreshToken createRefreshToken(User user, String deviceInfo,
                                           String ipAddress, String userAgent) {

        // Enforce max concurrent sessions per user
        long activeSessions = refreshTokenRepository
                .countActiveSessions(user.getId(), Instant.now());

        if (activeSessions >= AppConstants.MAX_SESSIONS_PER_USER) {
            revokeOldestSession(user.getId());
            log.info("Max sessions reached for userId={}, revoked oldest", user.getId());
        }

        RefreshToken token = RefreshToken.builder()
                .token(UUID.randomUUID().toString())   // cryptographically random UUID
                .user(user)
                .expiresAt(Instant.now().plus(refreshExpirationDays, ChronoUnit.DAYS))
                .deviceInfo(deviceInfo)
                .ipAddress(ipAddress)
                .userAgent(userAgent != null && userAgent.length() > 500
                        ? userAgent.substring(0, 500) : userAgent)
                .build();

        return refreshTokenRepository.save(token);
    }

    // ── Rotate (exchange old for new) ─────────────────────────────────────────

    /**
     * TOKEN ROTATION SECURITY:
     *
     * Every call to /auth/refresh:
     *   1. Old refresh token is REVOKED
     *   2. New refresh token is ISSUED
     *
     * WHY ROTATE:
     *   Without rotation: if attacker steals refresh token, they use it indefinitely.
     *   With rotation: each use invalidates the previous token.
     *
     * REUSE DETECTION:
     *   If attacker steals a ROTATED (already used) token and tries to use it:
     *   We detect: this token was already rotated → account may be compromised.
     *   Response: REVOKE ALL sessions for this user.
     *   The legitimate user must log in again (minor inconvenience).
     *   The attacker loses ALL their tokens (major security win).
     */
    @Transactional
    public RefreshToken rotate(String tokenValue) {
        RefreshToken existing = refreshTokenRepository.findByToken(tokenValue)
                .orElseThrow(() -> AppException.badRequest("Invalid refresh token"));

        // Detect token reuse (potential security breach)
        if (existing.isRevoked()) {
            log.warn("⚠️ SECURITY: Revoked refresh token reuse for userId={}. " +
                    "Revoking ALL sessions.", existing.getUser().getId());
            refreshTokenRepository.revokeAllForUser(
                    existing.getUser().getId(), "SECURITY_REUSE_DETECTED");
            throw AppException.badRequest(
                    "Refresh token already used. Please log in again.");
        }

        if (existing.isExpired()) {
            throw AppException.badRequest("Refresh token expired. Please log in again.");
        }

        // Revoke the current token (rotation)
        existing.revoke("ROTATED");
        refreshTokenRepository.save(existing);

        // Issue a new refresh token
        RefreshToken newToken = RefreshToken.builder()
                .token(UUID.randomUUID().toString())
                .user(existing.getUser())
                .expiresAt(Instant.now().plus(refreshExpirationDays, ChronoUnit.DAYS))
                .deviceInfo(existing.getDeviceInfo())
                .ipAddress(existing.getIpAddress())
                .userAgent(existing.getUserAgent())
                .lastUsedAt(Instant.now())
                .build();

        return refreshTokenRepository.save(newToken);
    }

    // ── Revocation ─────────────────────────────────────────────────────────────

    @Transactional
    public void revokeToken(String tokenValue) {
        refreshTokenRepository.findByToken(tokenValue).ifPresent(rt -> {
            rt.revoke("LOGOUT");
            refreshTokenRepository.save(rt);
        });
    }

    @Transactional
    public int revokeAllForUser(Long userId) {
        return refreshTokenRepository.revokeAllForUser(userId, "LOGOUT_ALL");
    }

    // ── Sessions ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RefreshToken> getActiveSessions(Long userId) {
        return refreshTokenRepository
                .findByUserIdAndRevokedFalseOrderByLastUsedAtDesc(userId);
    }

    // ── Cleanup: runs nightly at 02:00 ────────────────────────────────────────

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void cleanupExpiredTokens() {
        int deleted = refreshTokenRepository.deleteExpiredAndRevoked(Instant.now());
        if (deleted > 0)
            log.info("Cleaned up {} expired/revoked refresh tokens", deleted);
    }

    // ── Private ────────────────────────────────────────────────────────────────

    private void revokeOldestSession(Long userId) {
        refreshTokenRepository
                .findByUserIdAndRevokedFalseOrderByLastUsedAtDesc(userId)
                .stream()
                .reduce((first, second) -> second)
                .ifPresent(oldest -> {
                    oldest.revoke("SESSION_LIMIT_EXCEEDED");
                    refreshTokenRepository.save(oldest);
                });
    }
}
