package com.cityapp.ratelimit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import com.cityapp.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis-backed, distributed token-bucket rate limiting.
 *
 * This filter runs before Spring Security, so it must not trust identity
 * claims from an Authorization header: those claims have not been validated
 * yet. All filter-level buckets therefore use the client IP. OTP sends have
 * an additional phone-number limit in OtpService, which reads the validated
 * JSON request DTO rather than servlet query parameters.
 *
 * Client IP policy: X-Forwarded-For is considered only when the direct peer is
 * in a private/loopback range. Deployments must ensure only trusted proxies can
 * reach the service on those network interfaces and that proxies overwrite XFF.
 */
@Slf4j
@Component
@Order(1)
public class RateLimitFilter implements Filter {

    private final ProxyManager<byte[]> proxyManager;
    private final ObjectMapper objectMapper;
    private final Supplier<BucketConfiguration> loginBucketConfig;
    private final Supplier<BucketConfiguration> registerBucketConfig;
    private final Supplier<BucketConfiguration> otpBucketConfig;
    private final Supplier<BucketConfiguration> generalApiBucketConfig;

    public RateLimitFilter(
            @Autowired(required = false) ProxyManager<byte[]> proxyManager,
            ObjectMapper objectMapper,
            @Qualifier("loginBucketConfig") Supplier<BucketConfiguration> loginBucketConfig,
            @Qualifier("registerBucketConfig") Supplier<BucketConfiguration> registerBucketConfig,
            @Qualifier("otpBucketConfig") Supplier<BucketConfiguration> otpBucketConfig,
            @Qualifier("generalApiBucketConfig") Supplier<BucketConfiguration> generalApiBucketConfig) {
        this.proxyManager = proxyManager;
        this.objectMapper = objectMapper;
        this.loginBucketConfig = loginBucketConfig;
        this.registerBucketConfig = registerBucketConfig;
        this.otpBucketConfig = otpBucketConfig;
        this.generalApiBucketConfig = generalApiBucketConfig;
    }

    @Value("${cityapp.ratelimit.enabled:true}")
    private boolean rateLimitEnabled;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        if (!rateLimitEnabled || proxyManager == null) {
            chain.doFilter(req, res);
            return;
        }

        String path = request.getRequestURI();
        String method = request.getMethod();
        String ip = extractClientIp(request);
        String bucketKey = null;
        Supplier<BucketConfiguration> bucketConfig = null;

        if ("POST".equals(method) && path.endsWith("/auth/login")) {
            bucketKey = "rl:login:ip:" + ip;
            bucketConfig = loginBucketConfig;
        } else if ("POST".equals(method) && path.endsWith("/auth/register")) {
            bucketKey = "rl:register:ip:" + ip;
            bucketConfig = registerBucketConfig;
        } else if ("POST".equals(method)
                && (path.endsWith("/auth/otp/send") || path.endsWith("/auth/otp/verify"))) {
            // IP bucket protects the endpoint even when JSON is malformed or
            // phone is missing. OtpService separately limits by normalized phone.
            bucketKey = "rl:otp:ip:" + ip;
            bucketConfig = otpBucketConfig;
        } else if (path.startsWith("/api/")) {
            // Do not decode an unverified JWT to choose a rate-limit identity.
            bucketKey = "rl:api:ip:" + ip;
            bucketConfig = generalApiBucketConfig;
        }

        if (bucketKey == null || bucketConfig == null) {
            chain.doFilter(req, res);
            return;
        }

        Bucket bucket = proxyManager.builder()
                .build(bucketKey.getBytes(StandardCharsets.UTF_8), bucketConfig);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.setHeader("X-Rate-Limit-Remaining",
                    String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(req, res);
            return;
        }

        long waitForRefill = Math.max(1L,
                (long) Math.ceil(probe.getNanosToWaitForRefill() / 1_000_000_000.0));
        // Never log the full bucket key: it may contain an IP address.
        log.warn("Rate limit exceeded: path={} waitSeconds={}", path, waitForRefill);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(waitForRefill));
        response.setHeader("X-Rate-Limit-Retry-After-Seconds", String.valueOf(waitForRefill));

        ApiResponse<Void> body = ApiResponse.error(
                "RATE_LIMIT_EXCEEDED",
                "Too many requests. Please wait " + waitForRefill
                        + " seconds before trying again.");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    String extractClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (isPrivateOrLoopback(remoteAddr)) {
            String xForwardedFor = request.getHeader("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isBlank()) {
                String first = xForwardedFor.split(",")[0].trim();
                if (!first.isBlank()) {
                    return first;
                }
            }
        }
        return remoteAddr;
    }

    private boolean isPrivateOrLoopback(String ip) {
        if (ip == null) return false;
        if (ip.startsWith("127.") || ip.startsWith("10.")
                || ip.startsWith("192.168.") || ip.equals("::1")
                || ip.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        if (ip.startsWith("172.")) {
            String[] parts = ip.split("\\.");
            if (parts.length >= 2) {
                try {
                    int secondOctet = Integer.parseInt(parts[1]);
                    return secondOctet >= 16 && secondOctet <= 31;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
        }
        return false;
    }
}
