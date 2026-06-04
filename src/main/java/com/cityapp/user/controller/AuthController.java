package com.cityapp.user.controller;

import com.cityapp.common.response.ApiResponse;
import com.cityapp.user.dto.RegisterRequest;
import com.cityapp.user.dto.UserResponse;
import com.cityapp.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Handles authentication endpoints: register, login, logout, OTP.
 * Login and logout require JWT — implemented in Phase 4.
 * Registration is here (no JWT needed — user doesn't exist yet).
 *
 * WHY SEPARATE AUTH AND USER CONTROLLERS:
 *   AuthController: endpoints that don't require authentication (public)
 *   UserController: endpoints that DO require authentication (protected)
 *
 *   This separation makes SecurityConfig cleaner:
 *   requestMatchers("/api/v1/auth/**").permitAll()  ← the whole auth namespace
 *   vs. whitelisting specific paths on UserController.
 *
 * @RequestMapping("/api/v1/auth"):
 *   Prefix for all auth endpoints.
 *   Convention: /api/v1 for REST APIs.
 *   WHY /api/: separates API routes from any future static content routes.
 *   WHY /v1/: API versioning. When v2 is introduced, v1 still works.
 *
 * @RestController = @Controller + @ResponseBody:
 *   @Controller: marks class as web controller
 *   @ResponseBody: every return value is serialised to JSON
 *   Combined: every method returns JSON. No view template rendering.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    /**
     * Register a new user account.
     *
     * WHY ResponseEntity<ApiResponse<UserResponse>> not just ApiResponse<UserResponse>:
     *   ApiResponse<UserResponse>: Jackson serialises it. HTTP status is always 200.
     *   ResponseEntity<...>: WRAPS the response. Allows setting HTTP status code.
     *
     *   Registration success should return 201 Created (not 200 OK).
     *   201 = "I created a new resource as a result of this request."
     *   200 = "I processed your request and here is the result."
     *   Semantically: registration creates a user. 201 is correct.
     *
     *   HTTP status codes communicate meaning to clients and monitoring tools.
     *   POST /register returns 201 → Datadog shows 201s not 200s.
     *   Makes it easy to query: "show me all successful registrations today".
     *
     * @Valid triggers Bean Validation on RegisterRequest fields.
     *   Without @Valid: @NotBlank, @Email annotations do nothing.
     *   Invalid request reaches service. Service may throw or save bad data.
     *   With @Valid: validation runs before the method body.
     *   Invalid fields: MethodArgumentNotValidException → GlobalExceptionHandler → 422.
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserResponse>> register(
            @Valid @RequestBody RegisterRequest req) {

        UserResponse response = userService.register(req);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Registration successful"));
    }

    // ── Placeholder endpoints ────────────────────────

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<?>> login() {
        // (JWT Authentication)
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(ApiResponse.error("NOT_IMPLEMENTED",
                        "Login Will be implemented"));
    }
}
