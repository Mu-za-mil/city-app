package com.cityapp.auth.controller;

import com.cityapp.auth.dto.UserResponse;
import com.cityapp.auth.service.UserService;
import com.cityapp.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Administrative account-status operations owned by auth-service.
 *
 * Authorization is enforced here as well as at any calling service boundary.
 * Callers must not rely solely on authorization performed by the main backend.
 */
@RestController
@RequestMapping("/api/v1/auth/admin/users")
@RequiredArgsConstructor
public class AccountStatusAdminController {

    private final UserService userService;

    @PostMapping("/{userId}/suspend")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> suspend(
            @PathVariable Long userId) {
        UserResponse response = userService.suspendUser(userId);
        return ResponseEntity.ok(ApiResponse.ok(response, "User suspended successfully"));
    }

    @PostMapping("/{userId}/reinstate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> reinstate(
            @PathVariable Long userId,
            @AuthenticationPrincipal User administrator) {
        UserResponse response = userService.reinstateUser(userId);
        return ResponseEntity.ok(ApiResponse.ok(response, "User reinstated successfully"));
    }
}
