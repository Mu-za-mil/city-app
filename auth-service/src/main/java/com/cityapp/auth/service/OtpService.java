package com.cityapp.auth.service;

import com.cityapp.auth.entity.RefreshToken;
import com.cityapp.security.service.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import com.cityapp.common.constants.AppConstants;
import com.cityapp.common.exception.AppException;
import com.cityapp.common.event.EventPublisher;
import com.cityapp.common.event.OtpRequestEvent;
import com.cityapp.auth.dto.AuthResponse;
import com.cityapp.auth.dto.VerifyOtpRequest;
import com.cityapp.auth.entity.User;
import com.cityapp.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    private final StringRedisTemplate redisTemplate;
    private final UserRepository userRepository;
    private final EventPublisher eventPublisher;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;

    private static final SecureRandom random = new SecureRandom();

    /**
     * Increment and set the first-request expiry in one Redis operation.
     * Keeping INCR and EXPIRE separate can leave a permanent counter if the
     * application crashes between those commands.
     */
    private static final DefaultRedisScript<Long> OTP_THROTTLE_SCRIPT =
            new DefaultRedisScript<>(
                    "local count = redis.call('INCR', KEYS[1]); "
                            + "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]); end; "
                            + "return count;",
                    Long.class);

    public void generateAndSendOtp(String phone) {
        // 1. Throttle: max 5 OTP requests per hour per phone
        String throttleKey = AppConstants.REDIS_OTP_THROTTLE_PREFIX + phone;
        Long count = redisTemplate.execute(
                OTP_THROTTLE_SCRIPT, List.of(throttleKey), "3600");
        if (count == null || count > 5) {
            throw AppException.badRequest("Too many OTP requests. Please try again later.");
        }

        // 2. Generate a 6-digit OTP
        String otp = String.format("%06d", random.nextInt(1_000_000));

        // 3. Store OTP in Redis with expiry (5 minutes)
        String otpKey = AppConstants.REDIS_OTP_PREFIX + phone;
        redisTemplate.opsForValue().set(otpKey, otp, AppConstants.OTP_EXPIRY_SECONDS, TimeUnit.SECONDS);

        // 4. Find the user by phone and publish an OTP request event for email delivery
        User user = userRepository.findByPhone(phone).orElse(null);
        if (user != null) {
            eventPublisher.publishOtpRequested(
                    OtpRequestEvent.builder()
                            .eventId(EventPublisher.generateEventId())
                            .email(user.getEmail())
                            .name(user.getName())
                            .otp(otp)
                            .build());
            // Never include contact details or OTP values in logs.
            log.info("OTP delivery event published");
        } else {
            log.debug("OTP request received; no matching account, delivery event not published");
        }

        // 5. (Optional) Send SMS if enabled – you can call SmsService here.

    }

    /**
     * Verify and consume an OTP atomically. Redis executes this script as one
     * operation, so concurrent requests cannot both consume the same OTP or
     * race past the failed-attempt limit.
     *
     * Status values: 1 = verified, 0 = wrong OTP, -1 = missing/expired OTP,
     * -2 = attempt limit reached (locked).
     *
     * The attempt key is deliberately not cleared when a new OTP is issued.
     * Its TTL starts on the first wrong guess, giving a fixed lockout window.
     */
    private static final DefaultRedisScript<Long> OTP_VERIFY_SCRIPT =
            new DefaultRedisScript<>(
                    "local attempts = tonumber(redis.call('GET', KEYS[2]) or '0'); "
                            + "if attempts >= tonumber(ARGV[2]) then return -2; end; "
                            + "local stored = redis.call('GET', KEYS[1]); "
                            + "if not stored then return -1; end; "
                            + "if stored == ARGV[1] then "
                            + "redis.call('DEL', KEYS[1]); "
                            + "redis.call('DEL', KEYS[2]); "
                            + "return 1; "
                            + "end; "
                            + "local count = redis.call('INCR', KEYS[2]); "
                            + "if count == 1 then redis.call('EXPIRE', KEYS[2], ARGV[3]); end; "
                            + "if count >= tonumber(ARGV[2]) then "
                            + "redis.call('DEL', KEYS[1]); "
                            + "return -2; "
                            + "end; "
                            + "return 0;",
                    Long.class);

    public AuthResponse verifyOtp(VerifyOtpRequest req, HttpServletRequest httpRequest) {
        String otpKey = AppConstants.REDIS_OTP_PREFIX + req.getPhone();
        String attemptsKey = AppConstants.REDIS_OTP_ATTEMPTS_PREFIX + req.getPhone();

        Long result = redisTemplate.execute(
                OTP_VERIFY_SCRIPT,
                List.of(otpKey, attemptsKey),
                req.getOtp(),
                Integer.toString(AppConstants.OTP_MAX_VERIFY_ATTEMPTS),
                Integer.toString(AppConstants.OTP_VERIFY_LOCKOUT_SECONDS));

        if (result == null) {
            throw AppException.serviceUnavailable("Unable to verify OTP. Please try again.");
        }
        if (result == -1L) {
            throw AppException.badRequest(
                    "OTP has expired or was never requested. Please request a new one.");
        }
        if (result == 0L) {
            throw AppException.badRequest("Invalid OTP. Please check and try again.");
        }
        if (result == -2L) {
            throw AppException.of(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "OTP_ATTEMPTS_EXCEEDED",
                    "Too many incorrect OTP attempts. Please request a new OTP later.");
        }
        if (result != 1L) {
            throw AppException.serviceUnavailable("Unable to verify OTP. Please try again.");
        }

        User user = userRepository.findByPhone(req.getPhone())
                .orElseThrow(() -> AppException.notFound("User not found"));

        log.info("OTP verification successful");

        // Step 6: Generate access token (short-lived JWT)
        String accessToken = jwtService.generateAccessToken(user);

        // Step 7: Create refresh token (long-lived, stored in DB)
        String deviceInfo = extractDeviceInfo(httpRequest);
        String ipAddress  = extractClientIp(httpRequest);
        String userAgent  = httpRequest.getHeader("User-Agent");

        RefreshToken refreshToken = refreshTokenService
                .createRefreshToken(user, deviceInfo, ipAddress, userAgent);

        log.info("Authentication successful via OTP");

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken.getToken())
                .accessTokenExpiresIn(900L)   // 15 minutes in seconds
                .userId(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole())
                .build();
    }

    // Private methods
    private String extractDeviceInfo(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        if (ua == null) return "Unknown Device";
        if (ua.contains("iPhone")) return "iPhone";
        if (ua.contains("Android")) return "Android";
        if (ua.contains("iPad")) return "iPad";
        if (ua.contains("Windows")) return "Windows PC";
        if (ua.contains("Macintosh")) return "Mac";
        return "Browser";
    }

    private String extractClientIp(HttpServletRequest request) {
        // Only trust X-Forwarded-For from private/loopback IPs (our load balancer)
        // Public IPs sending XFF = spoofing attempt
        String remoteAddr = request.getRemoteAddr();
        if (isPrivateOrLoopback(remoteAddr)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                // Take the rightmost non-private IP (the real client)
                String[] ips = xff.split(",");
                for (int i = ips.length - 1; i >= 0; i--) {
                    String ip = ips[i].trim();
                    if (!isPrivateOrLoopback(ip)) return ip;
                }
                return ips[0].trim();
            }
        }
        return remoteAddr;
    }

    private boolean isPrivateOrLoopback(String ip) {
        return ip != null && (
                ip.startsWith("127.") || ip.startsWith("10.") ||
                        ip.startsWith("172.1") || ip.startsWith("172.2") ||
                        ip.startsWith("172.3") || ip.startsWith("192.168.") ||
                        ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1")
        );
    }
}
