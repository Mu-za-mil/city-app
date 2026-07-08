package com.cityapp.auth.service;

import com.cityapp.auth.entity.RefreshToken;
import jakarta.servlet.http.HttpServletRequest;
import com.cityapp.auth.common.constants.AppConstants;
import com.cityapp.auth.common.exception.AppException;
import com.cityapp.notification.service.EmailService;
import com.cityapp.auth.dto.AuthResponse;
import com.cityapp.auth.dto.VerifyOtpRequest;
import com.cityapp.auth.entity.User;
import com.cityapp.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    private final StringRedisTemplate redisTemplate;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;

    private static final SecureRandom random = new SecureRandom();

    public String generateAndSendOtp(String phone) {
        // 1. Throttle: max 5 OTP requests per hour per phone
        String throttleKey = AppConstants.REDIS_OTP_THROTTLE_PREFIX + phone;
        Long count = redisTemplate.opsForValue().increment(throttleKey);
        if (count == null || count > 5) {
            throw AppException.badRequest("Too many OTP requests. Please try again later.");
        }
        if (count == 1) {
            redisTemplate.expire(throttleKey, Duration.ofHours(1));
        }

        // 2. Generate a 6-digit OTP
        String otp = String.format("%06d", random.nextInt(1_000_000));

        // 3. Store OTP in Redis with expiry (5 minutes)
        String otpKey = AppConstants.REDIS_OTP_PREFIX + phone;
        redisTemplate.opsForValue().set(otpKey, otp, AppConstants.OTP_EXPIRY_SECONDS, TimeUnit.SECONDS);

        // 4. Find the user by phone to get their email
        User user = userRepository.findByPhone(phone).orElse(null);
        if (user != null) {
            emailService.sendOtpEmail(user.getEmail(), user.getName(), otp);
            log.info("OTP sent via email to {} for phone {}", user.getEmail(), phone);
        } else {
            log.debug("OTP generated for phone {} but no user found; email not sent", phone);
        }

        // 5. (Optional) Send SMS if enabled – you can call SmsService here.

        return otp;  // Return OTP for dev debugging (remove in production)
    }

    public AuthResponse verifyOtp(VerifyOtpRequest req, HttpServletRequest httpRequest) {
        String key = AppConstants.REDIS_OTP_PREFIX + req.getPhone();
        String stored = redisTemplate.opsForValue().get(key);

        if (stored == null) {
            throw AppException.badRequest(
                    "OTP has expired or was never requested. Please request a new one.");
        }
        if (!stored.equals(req.getOtp())) {
            throw AppException.badRequest("Invalid OTP. Please check and try again.");
        }
        redisTemplate.delete(key);  // Clear OTP after successful verification

        User user = userRepository.findByPhone(req.getPhone())
                .orElseThrow(() -> AppException.notFound("User not found"));

        log.info("OTP verified for phone={} userId={}", req.getPhone(), user.getId());

        // Step 6: Generate access token (short-lived JWT)
        String accessToken = jwtService.generateAccessToken(user);

        // Step 7: Create refresh token (long-lived, stored in DB)
        String deviceInfo = extractDeviceInfo(httpRequest);
        String ipAddress  = extractClientIp(httpRequest);
        String userAgent  = httpRequest.getHeader("User-Agent");


        RefreshToken refreshToken = refreshTokenService
                .createRefreshToken(user, deviceInfo, ipAddress, userAgent);

        log.info("User logged in: id={} email={} device={}",
                user.getId(), user.getEmail(), deviceInfo);



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