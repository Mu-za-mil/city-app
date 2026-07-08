package com.cityapp.auth.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.time.Instant;

/**
 * Standard API response envelope for ALL endpoints in the application.
 *
 * EVERY response — success or error — uses this format:
 * {
 *   "success": true/false,
 *   "message": "optional human-readable message",
 *   "data": { ...the actual payload... },
 *   "errorCode": "ONLY_ON_ERRORS",
 *   "timestamp": "2025-01-15T14:32:01.234Z"
 * }
 *
 * WHY AN ENVELOPE:
 *   Without it: different endpoints return different shapes.
 *   Frontend must check for different fields on every endpoint.
 *   With it: always check response.success, always read from response.data.
 *   One pattern. Forever.
 *
 * WHY @JsonInclude(NON_NULL):
 *   "errorCode" should not appear in successful responses.
 *   "message" should not appear if there is no message.
 *   NON_NULL: null fields are omitted from the JSON output.
 *   Without it: every response contains "errorCode": null.
 *   With it: errorCode only appears when it has a value.
 *
 * WHY Instant for timestamp (not LocalDateTime or Date):
 *   Instant is always UTC. No timezone ambiguity.
 *   LocalDateTime has no timezone = ambiguous.
 *   Date is a legacy class with timezone inconsistencies.
 *   Always use Instant for API timestamps.
 */

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final boolean success;
    private final String message;
    private final T data;
    private final String errorCode;
    private final Instant timestamp;

    // Private constructor — use static factory methods below
    private ApiResponse(boolean success, String message, T data, String errorCode) {
        this.success = success;
        this.message = message;
        this.data = data;
        this.errorCode = errorCode;
        this.timestamp = Instant.now();
    }

    // ── Success responses ─────────────────────────────────────────────────────

    /**
     * Success with data payload.
     * Usage: return ApiResponse.ok(orderResponse);
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, null, data, null);
    }

    /**
     * Success with data and a human-readable message.
     * Usage: return ApiResponse.ok(user, "Registration successful");
     */
    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(true, message, data, null);
    }

    /**
     * Success with no data payload.
     * Usage: return ApiResponse.ok("Logged out successfully");
     * Used for: logout, mark-read, delete operations.
     */
    public static ApiResponse<Void> ok(String message) {
        return new ApiResponse<>(true, message, null, null);
    }

    // ── Error responses ───────────────────────────────────────────────────────

    /**
     * Error response.
     * Usage: ApiResponse.error("VALIDATION_ERROR", "Email is invalid")
     * NOTE: You rarely call this directly. GlobalExceptionHandler calls it.
     */
    public static <T> ApiResponse<T> error(String errorCode, String message) {
        return new ApiResponse<>(false, message, null, errorCode);
    }

    /**
     * Error response with data (e.g., validation errors: field → error map).
     * Usage: ApiResponse.error("VALIDATION_ERROR", "Validation failed", fieldErrors)
     */
    public static <T> ApiResponse<T> error(String errorCode, String message, T data) {
        return new ApiResponse<>(false, message, data, errorCode);
    }
}

