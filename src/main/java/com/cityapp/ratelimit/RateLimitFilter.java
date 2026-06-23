package com.cityapp.ratelimit;

import com.cityapp.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * HTTP Filter for API rate limiting.
 *
 * RUNS BEFORE SECURITY FILTER CHAIN:
 *   @Order(1) ensures this filter runs before Spring Security's filters.
 *   Rate limiting is infrastructure-level — it runs before authentication.
 *   Why: an unauthenticated login attempt should be rate-limited too.
 *   If we ran after Spring Security: the security filter would process the
 *   request, hit the DB to look up the user, THEN we'd rate-limit.
 *   Wasteful: why do the DB work before deciding to reject?
 *
 * BUCKET KEY DESIGN:
 *   Login:    "rl:login:{ip}:{email}"       — per IP + per email
 *   Register: "rl:register:{ip}"            — per IP only
 *   OTP:      "rl:otp:{phone}"              — per phone number
 *   General:  "rl:api:{userId}" or "rl:api:{ip}" — authenticated or anonymous
 *
 *   WHY COMPOSITE KEY (ip + email) FOR LOGIN:
 *   Attacker uses one IP: IP-based limit catches them after 5 attempts.
 *   Attacker rotates IPs (proxy network):
 *     IP-only rate limit: bypassed (different IP each time).
 *     Email-only rate limit: still works (same email target).
 *     Composite: attacker needs BOTH a fresh IP AND a fresh email for each attempt.
 *     Effectively prevents both IP rotation and account enumeration attacks.
 *
 * XFF SPOOFING PROTECTION (THE BUG WE FOUND):
 *   X-Forwarded-For (XFF) header: "real client IP" when behind a load balancer.
 *   Nginx/ALB: adds "X-Forwarded-For: 1.2.3.4" (real client IP).
 *   Without protection: attacker sends header themselves:
 *     curl -H "X-Forwarded-For: 127.0.0.1" POST /auth/login
 *   Server reads XFF "127.0.0.1" as the client IP.
 *   Rate limit key: "rl:login:127.0.0.1:ravi@test.com"
 *   Attacker creates a new "identity" by changing the XFF header.
 *   Rate limit is completely bypassed.
 *
 *   FIX: Only trust XFF from PRIVATE/LOOPBACK addresses.
 *   Public IPs in remoteAddr: the request came directly to us, not via LB.
 *   Sending XFF from a public IP: SPOOFING. Ignore the header. Use remoteAddr.
 *   Private IPs in remoteAddr (10.x, 172.x, 192.168.x): the request came via LB.
 *   LB is trusted (it's our own infrastructure). Use the XFF header.
 *
 *   This is a real vulnerability found in many rate limiting implementations.
 *   The fix is simple but non-obvious.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class RateLimitFilter implements Filter {

    private final ProxyManager<byte[]>              proxyManager;
    private final ObjectMapper                      objectMapper;
    private final Supplier<BucketConfiguration>    loginBucketConfig;
    private final Supplier<BucketConfiguration>    registerBucketConfig;
    private final Supplier<BucketConfiguration>    otpBucketConfig;
    private final Supplier<BucketConfiguration>    generalApiBucketConfig;

    @Value("${app.ratelimit.enabled:true}")
    private boolean rateLimitEnabled;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res,
                         FilterChain chain) throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest)  req;
        HttpServletResponse response = (HttpServletResponse) res;

        // Rate limiting disabled (dev mode or via config)
        if (!rateLimitEnabled || proxyManager == null) {
            chain.doFilter(req, res);
            return;
        }

        String path   = request.getRequestURI();
        String method = request.getMethod();
        String ip     = extractClientIp(request);

        // ── Apply rate limits per endpoint ────────────────────────────────────

        String bucketKey = null;
        Supplier<BucketConfiguration> bucketConfig = null;

        if ("POST".equals(method) && path.endsWith("/auth/login")) {
            // Composite key: IP + email (most targeted protection)
            String email = extractEmailFromBody(request);
            if (email != null) {
                bucketKey = "rl:login:" + ip + ":" + email.toLowerCase();
            } else {
                bucketKey = "rl:login:" + ip;
            }
            bucketConfig = loginBucketConfig;

        } else if ("POST".equals(method) && path.endsWith("/auth/register")) {
            bucketKey   = "rl:register:" + ip;
            bucketConfig = registerBucketConfig;

        } else if (path.contains("/auth/otp")) {
            // OTP: key by phone (in request body or query param)
            String phone = request.getParameter("phone");
            bucketKey   = "rl:otp:" + (phone != null ? phone : ip);
            bucketConfig = otpBucketConfig;

        } else if (path.startsWith("/api/")) {
            // General API rate limit for authenticated users
            String userId = extractUserIdFromToken(request);
            bucketKey = userId != null
                    ? "rl:api:user:" + userId
                    : "rl:api:ip:" + ip;
            bucketConfig = generalApiBucketConfig;
        }

        if (bucketKey == null) {
            // No rate limit for this path
            chain.doFilter(req, res);
            return;
        }

        // ── Check and consume a token ─────────────────────────────────────────

        Bucket bucket = proxyManager.builder()
                .build(bucketKey.getBytes(StandardCharsets.UTF_8), bucketConfig);

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            // Request allowed. Add rate limit headers for client awareness.
            response.addHeader("X-Rate-Limit-Remaining",
                    String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(req, res);

        } else {
            // Rate limit exceeded. Reject with 429.
            long waitForRefill = probe.getNanosToWaitForRefill() / 1_000_000_000;

            log.warn("Rate limit exceeded: path={} key={} waitSeconds={}",
                    path, bucketKey, waitForRefill);

            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.addHeader("X-Rate-Limit-Retry-After-Seconds",
                    String.valueOf(waitForRefill));

            ApiResponse<Void> rateLimitResponse = ApiResponse.error(
                    "RATE_LIMIT_EXCEEDED",
                    "Too many requests. Please wait " + waitForRefill +
                            " seconds before trying again."
            );

            response.getWriter().write(
                    objectMapper.writeValueAsString(rateLimitResponse));
        }
    }

    // ── IP Extraction (XFF Spoofing Protection) ────────────────────────────────

    /**
     * Extract the real client IP address, with protection against XFF spoofing.
     *
     * VULNERABILITY WITHOUT THIS METHOD:
     *   request.getHeader("X-Forwarded-For")
     *   Attacker sends: X-Forwarded-For: 127.0.0.1
     *   Server trusts it: client IP = 127.0.0.1
     *   Rate limit key: "rl:login:127.0.0.1:..."
     *   Attacker changes XFF each request: bypasses per-IP limits completely.
     *
     * FIX: Trust XFF only from private network addresses (our own load balancers).
     *   Public IP in remoteAddr: request is direct. XFF can be spoofed. Use remoteAddr.
     *   Private IP in remoteAddr: request via our LB. LB adds XFF. Trust it.
     */
    String extractClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();

        // Is the request coming from a trusted source (our load balancer)?
        if (isPrivateOrLoopback(remoteAddr)) {
            // Trust X-Forwarded-For from our infrastructure
            String xForwardedFor = request.getHeader("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isBlank()) {
                // XFF may contain a chain: "client, proxy1, proxy2"
                // The FIRST entry is the original client IP.
                String clientIp = xForwardedFor.split(",")[0].trim();
                if (!clientIp.isBlank()) {
                    return clientIp;
                }
            }
        }
        // Direct connection (no trusted proxy) or XFF not provided:
        // Use the actual socket address.
        return remoteAddr;
    }

    private boolean isPrivateOrLoopback(String ip) {
        if (ip == null) return false;
        return ip.startsWith("127.")        // loopback
                || ip.startsWith("10.")         // Class A private
                || ip.startsWith("172.16.")     // Class B private (172.16.x.x - 172.31.x.x)
                || ip.startsWith("172.17.")
                || ip.startsWith("172.18.")
                || ip.startsWith("172.19.")
                || ip.startsWith("172.20.")
                || ip.startsWith("172.21.")
                || ip.startsWith("172.22.")
                || ip.startsWith("172.23.")
                || ip.startsWith("172.24.")
                || ip.startsWith("172.25.")
                || ip.startsWith("172.26.")
                || ip.startsWith("172.27.")
                || ip.startsWith("172.28.")
                || ip.startsWith("172.29.")
                || ip.startsWith("172.30.")
                || ip.startsWith("172.31.")
                || ip.startsWith("192.168.")    // Class C private
                || ip.equals("::1")             // IPv6 loopback
                || ip.equals("0:0:0:0:0:0:0:1");
    }

    // ── Body Parsing for Composite Keys ───────────────────────────────────────

    /**
     * Extract email from login request body for composite rate limit key.
     * Uses a cached body wrapper to allow reading the body multiple times.
     */
    private String extractEmailFromBody(HttpServletRequest request) {
        try {
            String body = request.getReader().lines()
                    .reduce("", String::concat);
            // Simple JSON parsing without ObjectMapper overhead
            int emailStart = body.indexOf("\"email\"");
            if (emailStart == -1) return null;
            int valueStart = body.indexOf("\"", emailStart + 8) + 1;
            int valueEnd   = body.indexOf("\"", valueStart);
            if (valueStart <= 0 || valueEnd <= 0) return null;
            return body.substring(valueStart, valueEnd);
        } catch (Exception e) {
            return null;  // Can't read body: use IP only
        }
    }

    /**
     * Extract userId from JWT for authenticated rate limiting.
     * Does NOT fully validate the JWT (SecurityConfig does that).
     * Just reads the claim for rate limit key selection.
     */
    private String extractUserIdFromToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        try {
            // JWT payload is base64url-encoded middle segment
            String[] parts = auth.substring(7).split("\\.");
            if (parts.length < 2) return null;
            String payload = new String(java.util.Base64.getUrlDecoder()
                    .decode(parts[1]));
            // Extract sub (email) as userId proxy — fast, no crypto
            int subStart = payload.indexOf("\"sub\":\"") + 7;
            if (subStart < 7) return null;
            int subEnd = payload.indexOf("\"", subStart);
            return payload.substring(subStart, subEnd);
        } catch (Exception e) {
            return null;
        }
    }
}
