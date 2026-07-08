package com.cityapp.auth.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * The ONE and ONLY exception class used by all service layers.
 *
 * WHY ONE EXCEPTION CLASS:
 *
 * Traditional approach (anti-pattern):
 *   UserNotFoundException extends RuntimeException { }
 *   StoreNotFoundException extends RuntimeException { }
 *   OrderNotFoundException extends RuntimeException { }
 *   InsufficientStockException extends RuntimeException { }
 *   DuplicateEmailException extends RuntimeException { }
 *   ... 20+ exception classes ...
 *
 * Problems with that approach:
 *   1. 20+ files to create and maintain.
 *   2. GlobalExceptionHandler needs 20+ @ExceptionHandler methods.
 *   3. Adding a new error type = new class + new handler.
 *   4. Every class is essentially the same: just a message + HTTP status.
 *
 * OUR APPROACH:
 *   One class. HTTP status embedded. Static factory methods for common cases.
 *
 * Usage:
 *   throw AppException.notFound("Store not found: " + storeId);
 *   throw AppException.conflict("Email already registered");
 *   throw AppException.badRequest("Quantity must be positive");
 *   throw AppException.forbidden("You do not own this store");
 *
 * The GlobalExceptionHandler catches ALL AppExceptions:
 *   @ExceptionHandler(AppException.class)
 *   → reads getStatus() → returns correct HTTP code → consistent format.
 */
@Getter
public class AppException extends RuntimeException {

    private final HttpStatus status;
    private final String     errorCode;

    private AppException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status    = status;
        this.errorCode = errorCode;
    }

    // ── 404 Not Found ─────────────────────────────────────────────────────────
    public static AppException notFound(String message) {
        return new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    // ── 409 Conflict ──────────────────────────────────────────────────────────
    public static AppException conflict(String message) {
        return new AppException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    // ── 400 Bad Request ───────────────────────────────────────────────────────
    public static AppException badRequest(String message) {
        return new AppException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    // ── 403 Forbidden ─────────────────────────────────────────────────────────
    public static AppException forbidden(String message) {
        return new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    // ── 401 Unauthorized ──────────────────────────────────────────────────────
    public static AppException unauthorized(String message) {
        return new AppException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    // ── 422 Unprocessable Entity ──────────────────────────────────────────────
    public static AppException unprocessable(String message) {
        return new AppException(HttpStatus.UNPROCESSABLE_ENTITY,
                "UNPROCESSABLE_ENTITY", message);
    }

    // ── 503 Service Unavailable ───────────────────────────────────────────────
    public static AppException serviceUnavailable(String message) {
        return new AppException(HttpStatus.SERVICE_UNAVAILABLE,
                "SERVICE_UNAVAILABLE", message);
    }

    // ── Custom (for cases that need a specific HTTP status) ───────────────────
    public static AppException of(HttpStatus status, String errorCode, String message) {
        return new AppException(status, errorCode, message);
    }
}
