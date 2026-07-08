package com.cityapp.auth.service;

import com.cityapp.auth.entity.RefreshToken;
import com.cityapp.auth.service.RefreshTokenService;
import com.cityapp.auth.common.event.EventPublisher;
import com.cityapp.auth.common.event.UserRegisteredEvent;
import com.cityapp.auth.common.exception.AppException;
import com.cityapp.auth.common.response.PageResponse;
import com.cityapp.auth.service.JwtService;
import com.cityapp.auth.dto.*;
import com.cityapp.auth.entity.Role;
import com.cityapp.auth.entity.User;
import com.cityapp.auth.mapper.UserMapper;
import com.cityapp.auth.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service containing all User domain business logic.
 *
 * WHY @RequiredArgsConstructor:
 *   Lombok generates a constructor with ALL final fields as parameters.
 *   Spring uses this constructor to inject dependencies.
 *   Equivalent to writing:
 *   public UserService(UserRepository userRepository, UserMapper userMapper, ...) {
 *       this.userRepository = userRepository;
 *       this.userMapper = userMapper;
 *       ...
 *   }
 *   @RequiredArgsConstructor: generates this automatically.
 *   Combined with final fields: immutable service — dependencies set at construction.
 *
 * WHY IMPLEMENTS UserDetailsService:
 *   Spring Security needs a way to load user details during JWT validation.
 *   JwtAuthFilter extracts the email from the token and calls:
 *   userDetailsService.loadUserByUsername(email)
 *   This method is the implementation of that call.
 *   Returns User which implements UserDetails (from Phase 3.3).
 *
 * WHY @Transactional ON register():
 *   Registration involves: duplicate check + save.
 *   If save fails after duplicate check: transaction rolls back cleanly.
 *   Without @Transactional: save failure leaves no partial state
 *   (single operation, so OK without it too).
 *   BUT: as a principle, all write operations should be @Transactional.
 *   Reads: @Transactional(readOnly = true) — tells DB this is read-only,
 *   DB can optimise (no write locks acquired).
 *
 * WHY @Slf4j:
 *   Lombok generates: private static final Logger log = LoggerFactory.getLogger(...)
 *   Lets you write: log.info("User registered: {}", user.getEmail())
 *   The {} placeholder is evaluated lazily — string concat only if logging is enabled.
 *   Slightly more efficient than: log.info("User registered: " + email)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private final UserRepository  userRepository;
    private final UserMapper      userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final EventPublisher eventPublisher;
    // PasswordEncoder is a Spring Security bean defined in SecurityConfig (Phase 4).
    // We declare the dependency here. Spring will inject it.
    // This creates a chicken-and-egg situation: UserService needs SecurityConfig.
    // Resolution: UserService declares the interface (PasswordEncoder),
    // SecurityConfig provides the implementation (BCryptPasswordEncoder).
    // Spring resolves dependencies by interface type, not by class.

    // ── Registration ──────────────────────────────────────────────────────────

    @Transactional
    public UserResponse register(RegisterRequest req) {

        // BUSINESS RULE 1: Email must be unique
        if (userRepository.existsByEmail(req.getEmail())) {
            throw AppException.conflict("Email is already registered: " + req.getEmail());
            // WHY conflict (409) not bad request (400):
            // 400 = the request itself is malformed
            // 409 = the request is valid BUT conflicts with existing state
            // A duplicate email is a valid email — the conflict is with DB state.
        }

        // BUSINESS RULE 2: Phone must be unique if provided
        if (req.getPhone() != null && !req.getPhone().isBlank()
                && userRepository.existsByPhone(req.getPhone())) {
            throw AppException.conflict("Phone number is already registered");
        }

        // BUSINESS RULE 3: SUPER_ADMIN role cannot be self-assigned
        if (req.getRole() == Role.SUPER_ADMIN) {
            throw AppException.forbidden("SUPER_ADMIN role cannot be self-assigned");
        }

        // Convert request to entity (MapStruct)
        User user = userMapper.toEntity(req);

        // Hash the plaintext password BEFORE saving
        // BCrypt(plaintext, cost=12) → 60-char hash
        // The hash is mathematically impossible to reverse.
        // NEVER store plaintext passwords. Ever.
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));

        User saved = userRepository.save(user);

        eventPublisher.publishUserRegistered(
                UserRegisteredEvent.builder()
                        .eventId(EventPublisher.generateEventId())
                        .userId(saved.getId())
                        .email(saved.getEmail())
                        .name(saved.getName())
                        .phone(saved.getPhone())
                        .role(saved.getRole())
                        .registeredAt(saved.getCreatedAt())
                        .build());

        log.info("User registered: id={} email={} role={}",
                saved.getId(), saved.getEmail(), saved.getRole());

        return userMapper.toResponse(saved);
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Transactional
    public AuthResponse login(LoginRequest req, HttpServletRequest httpRequest) {

        // Step 1: Find user by email
        User user = userRepository.findByEmail(req.getEmail())
                .orElseThrow(() -> AppException.unauthorized(
                        "Invalid email or password"));
        // WHY same message for wrong email AND wrong password:
        // "Email not found" tells attacker which emails are registered.
        // "Invalid email or password" reveals nothing.
        // This is user enumeration protection.

        // Step 2: Check if account is enabled and not locked
        if (!user.isEnabled()) {
            throw AppException.unauthorized("Account has been suspended. Contact support.");
        }
        if (!user.isAccountNonLocked()) {
            throw AppException.unauthorized("Account is locked. Contact support.");
        }

        // Step 3: Verify password
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw AppException.unauthorized("Invalid email or password");
            // SAME message as "email not found" — no information leakage
        }

        // Step 4: Generate access token (short-lived JWT)
        String accessToken = jwtService.generateAccessToken(user);

        // Step 5: Create refresh token (long-lived, stored in DB)
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

// ── Refresh Token Exchange ─────────────────────────────────────────────────

    @Transactional
    public AuthResponse refreshTokens(String refreshTokenValue) {
        // Rotate the refresh token (old → new)
        RefreshToken newRefreshToken = refreshTokenService.rotate(refreshTokenValue);

        // Generate new access token for the user
        String newAccessToken = jwtService.generateAccessToken(newRefreshToken.getUser());

        return AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken.getToken())
                .accessTokenExpiresIn(900L)
                .userId(newRefreshToken.getUser().getId())
                .name(newRefreshToken.getUser().getName())
                .email(newRefreshToken.getUser().getEmail())
                .role(newRefreshToken.getUser().getRole())
                .build();
    }

// ── Logout ─────────────────────────────────────────────────────────────────

    public void logout(String accessToken, String refreshTokenValue) {
        // Blacklist the access token in Redis (prevents reuse before expiry)
        if (accessToken != null && accessToken.startsWith("Bearer ")) {
            jwtService.blacklist(accessToken.substring(7));
        }
        // Revoke the refresh token in DB
        if (refreshTokenValue != null && !refreshTokenValue.isBlank()) {
            refreshTokenService.revokeToken(refreshTokenValue);
        }
    }

    public void logoutAll(Long userId, String accessToken) {
        // Blacklist current access token
        if (accessToken != null && accessToken.startsWith("Bearer ")) {
            jwtService.blacklist(accessToken.substring(7));
        }
        // Revoke ALL refresh tokens for this user
        int revoked = refreshTokenService.revokeAllForUser(userId);
        log.info("User {} logged out from {} devices", userId, revoked);
    }

    // ── Profile Operations ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public UserResponse getProfile(Long userId) {
        User user = findUserById(userId);
        return userMapper.toResponse(user);
    }

    @Transactional
    public UserResponse updateProfile(Long userId, UpdateProfileRequest req) {
        User user = findUserById(userId);

        // Only update fields that are provided (not null)
        // This is a PATCH operation: update only what's sent.
        if (req.getName() != null && !req.getName().isBlank()) {
            user.setName(req.getName());
        }
        if (req.getProfileImageUrl() != null) {
            user.setProfileImageUrl(req.getProfileImageUrl());
        }

        User saved = userRepository.save(user);
        log.info("Profile updated: userId={}", userId);
        return userMapper.toResponse(saved);
    }

    // ── Admin Operations ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> listUsers(Pageable pageable) {
        Page<User> users = userRepository.findAll(pageable);
        return PageResponse.from(users.map(userMapper::toResponse));
        // users.map(): transforms Page<User> → Page<UserResponse>
        // PageResponse.from(): wraps Spring's Page in our stable DTO
    }

    @Transactional
    public UserResponse suspendUser(Long userId) {
        User user = findUserById(userId);
        if (user.getRole() == Role.SUPER_ADMIN) {
            throw AppException.forbidden("Cannot suspend a SUPER_ADMIN account");
        }
        user.setEnabled(false);
        User saved = userRepository.save(user);
        log.info("User suspended: userId={}", userId);
        return userMapper.toResponse(saved);
    }

    @Transactional
    public UserResponse reinstateUser(Long userId) {
        User user = findUserById(userId);
        user.setEnabled(true);
        User saved = userRepository.save(user);
        log.info("User reinstated: userId={}", userId);
        return userMapper.toResponse(saved);
    }

    // ── Spring Security: UserDetailsService ──────────────────────────────────

    /**
     * Called by Spring Security's JwtAuthFilter during every authenticated request.
     * Flow: request arrives → extract JWT → call loadUserByUsername(email)
     *       → get User entity → check enabled/locked → set SecurityContext
     *
     * WHY LOAD FROM DB ON EVERY REQUEST:
     *   The JWT doesn't contain: enabled status, role changes.
     *   If admin suspends a user: their JWT is still valid.
     *   But loading from DB: enabled=false → Spring Security rejects the request.
     *   Without this: suspended users can continue using the app until JWT expires.
     *
     * PERFORMANCE: 1 DB query per authenticated request.
     *   With connection pooling: <1ms per query.
     *   Acceptable. Use caching (Phase 8) if this becomes a bottleneck.
     */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "User not found with email: " + email));
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private User findUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> AppException.notFound("User not found: " + userId));
    }

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
