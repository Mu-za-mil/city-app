package com.cityapp.security.service;

import com.cityapp.common.constants.AppConstants;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Handles all JWT operations: generation, validation, blacklisting.
 *
 * ANATOMY OF A JWT:
 *   eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJyYXZpQHRlc3QuY29tIn0.ABC123
 *   │─────────────────│  │───────────────────────────────│  │────│
 *        Header               Payload (Claims)           Signature
 *
 *   Header (Base64): { "alg": "HS256", "typ": "JWT" }
 *   Payload (Base64): { "sub": "ravi@test.com", "iat": 1720000000, "exp": 1720086400 }
 *   Signature: HMACSHA256(base64(header) + "." + base64(payload), secretKey)
 *
 * WHY THE SIGNATURE IS CRITICAL:
 *   The payload is Base64-encoded — easily decoded, NOT encrypted.
 *   Anyone can read the payload. "sub": "ravi@test.com" is visible.
 *
 *   The SIGNATURE proves: "this token was created by someone who knows the secret."
 *   If an attacker modifies the payload: "sub": "admin@cityapp.com"
 *   The signature no longer matches the modified payload.
 *   Our validation detects this: SignatureException → 401 Unauthorized.
 *
 * WHY HMAC-SHA256 (symmetric):
 *   One key for both signing and verification.
 *   All services need the same secret.
 *   Simple. Fast. Sufficient for single-organization microservices.
 *   Alternative RS256 (asymmetric): auth-service has private key (signs),
 *   other services have public key (verify only). More secure but complex.
 *   We implement RS256 in Phase 17.
 *
 * WHY StringRedisTemplate NOT RedisTemplate<String, Object>:
 *   The blacklist value is always the string "1".
 *   StringRedisTemplate is optimised for string operations.
 *   No JSON serialisation overhead.
 *   Simpler: opsForValue().set("jwt:blacklist:xyz", "1")
 *   vs RedisTemplate: requires ObjectMapper configuration for string values.
 */
@Slf4j
@Service
public class JwtService {

    @Value("${cityapp.jwt.secret}")
    private String jwtSecret;

    @Value("${cityapp.jwt.access-token-expiration-ms:900000}")
    private long accessTokenExpirationMs;   // 15 minutes default

    private final StringRedisTemplate redisTemplate;

    public JwtService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // ── Token Generation ──────────────────────────────────────────────────────

    /**
     * Generates a signed JWT for the given user.
     *
     * Claims included:
     *   sub (subject): the user's email — used to reload User on each request
     *   iat (issued at): current timestamp
     *   exp (expiry): now + accessTokenExpirationMs
     *
     * WHY NOT include userId, role, name in the token:
     *   These change: user can be suspended, role can be changed.
     *   If cached in JWT: changes don't take effect until token expires.
     *   We load fresh UserDetails from DB on every request anyway.
     *   So: only store the minimum needed to look up the user (email).
     *
     *   Alternative: include userId, role → skip DB lookup on each request.
     *   Trade-off: faster but stale data possible until token expires.
     *   Our choice: DB lookup → always fresh data → suspension takes effect immediately.
     */
    public String generateAccessToken(UserDetails userDetails) {
        return Jwts.builder()
                .subject(userDetails.getUsername())   // email
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpirationMs))
                .signWith(getSignKey())
                .compact();
    }

    // ── Token Validation ──────────────────────────────────────────────────────

    /**
     * Full token validation: signature + expiry + blacklist + username match.
     *
     * Called by JwtAuthFilter on every authenticated request.
     *
     * @param token       the raw JWT string from the Authorization header
     * @param userDetails the User loaded from DB by loadUserByUsername
     * @return true if the token is valid and belongs to userDetails
     */
    public boolean isValid(String token, UserDetails userDetails) {
        try {
            // Check 1: Is it blacklisted? (logged out)
            // Fast Redis lookup: O(1). Short-circuit if blacklisted.
            if (isBlacklisted(token)) {
                log.debug("Token is blacklisted (user logged out)");
                return false;
            }

            // Check 2: Does the username in the token match the loaded user?
            // Prevents token substitution attacks.
            String username = extractUsername(token);
            if (!username.equals(userDetails.getUsername())) {
                log.debug("Token username mismatch");
                return false;
            }

            // Check 3: Is the token expired?
            if (isExpired(token)) {
                log.debug("Token is expired");
                return false;
            }

            return true;

        } catch (JwtException e) {
            // Covers: invalid signature, malformed token, unsupported algorithm
            log.debug("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    // ── Claims Extraction ─────────────────────────────────────────────────────

    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    public boolean isExpired(String token) {
        return extractAllClaims(token)
                .getExpiration()
                .before(new Date());
    }

    /**
     * How many milliseconds until this token expires.
     * Used when blacklisting: set Redis TTL = remaining validity.
     * When the token would have expired anyway: Redis key also expires.
     * No manual cleanup job needed.
     */
    public long getMillisUntilExpiry(String token) {
        Date expiry = extractAllClaims(token).getExpiration();
        long remaining = expiry.getTime() - System.currentTimeMillis();
        return Math.max(remaining, 0);
    }

    // ── Blacklisting (Logout) ─────────────────────────────────────────────────

    /**
     * Add token to Redis blacklist with TTL = remaining token lifetime.
     *
     * When TTL expires: Redis auto-deletes the key.
     * The token would be expired anyway at that point.
     * Zero maintenance. Zero cleanup jobs.
     *
     * KEY STRUCTURE: jwt:blacklist:{entire_token_string}
     * WHY ENTIRE TOKEN not just the jti (JWT ID):
     *   Our tokens don't include a jti claim (would add bytes to every token).
     *   The entire token string is unique enough as the Redis key.
     *   Downside: Redis keys can be large (JWT is ~200 chars).
     *   Alternative: hash the token to a fixed-size key (SHA-256 → 64 chars).
     *   For our scale: full token as key is fine.
     */
    public void blacklist(String token) {
        long ttlMs = getMillisUntilExpiry(token);
        if (ttlMs > 0) {
            redisTemplate.opsForValue().set(
                    AppConstants.REDIS_JWT_BLACKLIST_PREFIX + token,
                    "1",
                    ttlMs,
                    TimeUnit.MILLISECONDS
            );
            log.debug("Token blacklisted for {}ms", ttlMs);
        }
        // If ttlMs == 0: token is already expired. No need to blacklist.
    }

    public boolean isBlacklisted(String token) {
        return Boolean.TRUE.equals(
                redisTemplate.hasKey(
                        AppConstants.REDIS_JWT_BLACKLIST_PREFIX + token));
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSignKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        // Throws JwtException (and subtypes) if:
        // - Signature invalid (token was tampered)
        // - Token expired (exp < now)
        // - Token malformed (not valid base64url)
        // - Algorithm mismatch
    }

    /**
     * Convert the JWT secret string into a SecretKey object.
     *
     * WHY Keys.hmacShaKeyFor NOT new SecretKeySpec:
     *   Keys.hmacShaKeyFor validates the key length for HMAC-SHA256.
     *   If the key is too short: WeakKeyException thrown at startup.
     *   This is the enforcement of our JwtConfig startup validation.
     *   New SecretKeySpec: accepts any length, silently produces a weak key.
     */
    private SecretKey getSignKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }
}
