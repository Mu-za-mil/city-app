package com.cityapp.user.controller;

import com.cityapp.auth.entity.RefreshToken;
import com.cityapp.auth.service.RefreshTokenService;
import com.cityapp.common.response.ApiResponse;
import com.cityapp.user.dto.*;
import com.cityapp.user.entity.User;
import com.cityapp.user.service.OtpService;
import com.cityapp.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService         userService;
    private final RefreshTokenService refreshTokenService;
    private final OtpService otpService;

    // ── Registration ──────────────────────────────────────────────────────────

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest req,
            HttpServletRequest httpRequest) {

        // Register user, then immediately issue tokens (auto-login after register)
        UserResponse userResponse = userService.register(req);

        // Load the newly created user to issue tokens
        LoginRequest loginReq = new LoginRequest();
        loginReq.setEmail(req.getEmail());
        loginReq.setPassword(req.getPassword());

        AuthResponse authResponse = userService.login(loginReq, httpRequest);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(authResponse, "Registration successful"));
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    /**
     * Authenticate with email + password.
     * Returns access token (15 min) + refresh token (30 days).
     *
     * WHY 200 NOT 201 for login:
     *   Login doesn't CREATE a new resource — it retrieves an existing session.
     *   200 OK is semantically correct for authentication.
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest req,
            HttpServletRequest httpRequest) {

        AuthResponse response = userService.login(req, httpRequest);
        return ResponseEntity.ok(ApiResponse.ok(response, "Login successful"));
    }

    // ── Token Refresh ─────────────────────────────────────────────────────────

    /**
     * Exchange an expiring/expired access token for a new one.
     * This endpoint is PUBLIC — no JWT needed (access token may be expired).
     * Authentication is via the refresh token in the body.
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @RequestBody Map<String, String> body) {

        String refreshToken = body.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("MISSING_FIELD", "refreshToken is required"));
        }

        AuthResponse response = userService.refreshTokens(refreshToken);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    /**
     * Logout from current device only.
     * Blacklists access token in Redis + revokes refresh token in DB.
     */
    @DeleteMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) LogoutRequest req) {

        String refreshTokenValue = req != null ? req.getRefreshToken() : null;
        userService.logout(authHeader, refreshTokenValue);

        return ResponseEntity.ok(ApiResponse.ok("Logged out successfully"));
    }

    /**
     * Logout from ALL devices.
     * Revokes all refresh tokens for the current user.
     * Next request from any device will require login.
     */
    @DeleteMapping("/logout-all")
    public ResponseEntity<ApiResponse<Void>> logoutAll(
            @AuthenticationPrincipal User currentUser,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        userService.logoutAll(currentUser.getId(), authHeader);
        return ResponseEntity.ok(ApiResponse.ok("Logged out from all devices"));
    }

    // ── Session Management ────────────────────────────────────────────────────

    @GetMapping("/sessions")
    public ResponseEntity<ApiResponse<List<SessionResponse>>> getSessions(
            @AuthenticationPrincipal User currentUser) {

        List<SessionResponse> sessions = refreshTokenService
                .getActiveSessions(currentUser.getId())
                .stream()
                .map(rt -> SessionResponse.builder()
                        .id(rt.getId())
                        .deviceInfo(rt.getDeviceInfo())
                        .ipAddress(rt.getIpAddress())
                        .createdAt(rt.getCreatedAt())
                        .lastUsedAt(rt.getLastUsedAt())
                        .expiresAt(rt.getExpiresAt())
                        .build())
                .toList();

        return ResponseEntity.ok(ApiResponse.ok(sessions));
    }

    @PostMapping("/otp/send")
    public ResponseEntity<ApiResponse<OtpResponse>> sendOtp(@Valid @RequestBody OtpRequest request) {
        String otp = otpService.generateAndSendOtp(request.getPhone());
        OtpResponse response = OtpResponse.builder()
                .sent(true)
                .message("OTP sent successfully")
                .otp(otp)  // Include OTP in dev for easy testing – remove in production
                .build();
        return ResponseEntity.ok(ApiResponse.ok(response, "OTP sent"));
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<ApiResponse<AuthResponse>> verifyOtp(
            @Valid @RequestBody VerifyOtpRequest req,
            HttpServletRequest httpRequest) {

        AuthResponse authResponse = otpService.verifyOtp(req, httpRequest);
        return ResponseEntity.ok(ApiResponse.ok(authResponse, "Login successful"));
    }
}
