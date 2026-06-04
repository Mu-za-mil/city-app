package com.cityapp.user.controller;

import com.cityapp.common.response.ApiResponse;
import com.cityapp.user.dto.UpdateProfileRequest;
import com.cityapp.user.dto.UserResponse;
import com.cityapp.user.entity.User;
import com.cityapp.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Handles authenticated user profile operations.
 * All endpoints require a valid JWT (enforced by SecurityConfig in Phase 4).
 *
 * @AuthenticationPrincipal User user:
 *   Spring Security injects the currently authenticated user.
 *   This works because:
 *   1. JwtAuthFilter (Phase 4) validates the JWT
 *   2. Loads the User from DB via UserDetailsService
 *   3. Sets it as the principal in SecurityContext
 *   4. @AuthenticationPrincipal extracts it from SecurityContext
 *
 *   Without UserDetails implementation on User entity:
 *   @AuthenticationPrincipal returns Object → cast needed → fragile.
 *   With UserDetails on User: typed injection. No cast.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * Get the currently authenticated user's profile.
     * Uses @AuthenticationPrincipal to avoid DB lookup (user already loaded by filter).
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> getMe(
            @AuthenticationPrincipal User currentUser) {
        // currentUser is already loaded from DB by JwtAuthFilter.
        // No additional DB query needed.
        return ResponseEntity.ok(ApiResponse.ok(
                userService.getProfile(currentUser.getId())));
    }

    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> updateProfile(
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody UpdateProfileRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
                userService.updateProfile(currentUser.getId(), req)));
    }

    // ── Admin Endpoints ───────────────────────────────────────────────────────

    @PostMapping("/{userId}/suspend")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    // @PreAuthorize: checked before the method runs.
    // If user doesn't have SUPER_ADMIN role: AccessDeniedException → 403.
    // Layer 1 security: role check.
    // Layer 2 security: service checks additional conditions (can't suspend SUPER_ADMIN).
    public ResponseEntity<ApiResponse<UserResponse>> suspend(
            @PathVariable Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(
                userService.suspendUser(userId),
                "User suspended successfully"));
    }

    @PostMapping("/{userId}/reinstate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> reinstate(
            @PathVariable Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(
                userService.reinstateUser(userId),
                "User reinstated successfully"));
    }
}
