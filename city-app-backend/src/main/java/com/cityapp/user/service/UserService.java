package com.cityapp.user.service;

import com.cityapp.common.exception.AppException;
import com.cityapp.common.response.PageResponse;
import com.cityapp.user.dto.*;
import com.cityapp.user.entity.User;
import com.cityapp.user.mapper.UserMapper;
import com.cityapp.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
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
 * WHY @Slf4j:
 *   Lombok generates: private static final Logger log = LoggerFactory.getLogger(...)
 *   Lets you write: log.info("User profile updated: {}", user.getEmail())
 *   The {} placeholder is evaluated lazily — string concat only if logging is enabled.
 *   Slightly more efficient than: log.info("User profile updated: " + email)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private final UserRepository  userRepository;
    private final UserMapper      userMapper;

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

}
