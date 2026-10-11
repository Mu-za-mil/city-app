package com.cityapp.security.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import com.cityapp.security.principal.IdentifiedUserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Shared JWT service used by every servlet-based City App service.
 *
 * The auth-service uses generateAccessToken() and blacklist().
 * Resource services use extractUsername(), isValid(), and blacklist checks.
 *
 * JWT contract:
 *   sub = user's email
 *   iat = issued-at timestamp
 *   exp = expiry timestamp
 *   algorithm = HS256
 *
 * No user identity is accepted from X-User-* headers.
 */
@Slf4j
@Service
public class JwtService {

    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    @Value("${app.jwt.secret:${cityapp.jwt.secret}}")
    private String jwtSecret;

    @Value("${app.jwt.access-token-expiration-ms:${cityapp.jwt.access-token-expiration-ms:900000}}")
    private long accessTokenExpirationMs;

    private final StringRedisTemplate redisTemplate;

    public JwtService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String generateAccessToken(UserDetails userDetails) {
        var builder = Jwts.builder()
                .subject(userDetails.getUsername());

        if (userDetails instanceof IdentifiedUserDetails identifiedUser) {
            String userId = identifiedUser.getUserId();
            if (userId == null || userId.isBlank()) {
                throw new IllegalArgumentException(
                        "Cannot issue an access token without a persisted user ID");
            }
            builder.claim("uid", userId);
        }

        return builder
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpirationMs))
                .signWith(getSignKey(), Jwts.SIG.HS256)
                .compact();
    }

    public boolean isValid(String token, UserDetails userDetails) {
        try {
            if (isBlacklisted(token)) {
                return false;
            }

            String username = extractUsername(token);
            if (!username.equals(userDetails.getUsername())) {
                return false;
            }

            String tokenUserId = extractUserId(token);
            if (tokenUserId != null
                    && userDetails instanceof IdentifiedUserDetails identifiedUser
                    && !tokenUserId.equals(identifiedUser.getUserId())) {
                return false;
            }

            if (isExpired(token)) {
                return false;
            }

            return true;
        } catch (JwtException | NullPointerException e) {
            // Parser messages can contain attacker-controlled input. Log only the exception type.
            log.debug("JWT validation failed; exceptionType={}",
                    e.getClass().getSimpleName());
            return false;
        }
    }

    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    /**
     * Returns the signed stable user ID claim, or null for legacy tokens.
     * Callers must still validate the token before trusting this value.
     */
    public String extractUserId(String token) {
        return extractAllClaims(token).get("uid", String.class);
    }

    public boolean isExpired(String token) {
        return extractAllClaims(token)
                .getExpiration()
                .before(new Date());
    }

    public long getMillisUntilExpiry(String token) {
        Date expiry = extractAllClaims(token).getExpiration();
        return Math.max(expiry.getTime() - System.currentTimeMillis(), 0);
    }

    public void blacklist(String token) {
        long ttlMs = getMillisUntilExpiry(token);

        if (ttlMs > 0) {
            redisTemplate.opsForValue().set(
                    BLACKLIST_PREFIX + token,
                    "1",
                    ttlMs,
                    TimeUnit.MILLISECONDS
            );
            log.debug("Token blacklisted for {}ms", ttlMs);
        }
    }

    public boolean isBlacklisted(String token) {
        return Boolean.TRUE.equals(
                redisTemplate.hasKey(BLACKLIST_PREFIX + token));
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSignKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSignKey() {
        return Keys.hmacShaKeyFor(
                jwtSecret.getBytes(StandardCharsets.UTF_8));
    }
}
