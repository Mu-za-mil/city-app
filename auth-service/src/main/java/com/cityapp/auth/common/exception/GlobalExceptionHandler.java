package com.cityapp.auth.common.exception;

import com.cityapp.auth.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.HashMap;
import java.util.Map;

/**
 * The single net that catches every exception in the application
 * and converts it to a clean, consistent JSON response.
 *
 * @RestControllerAdvice = @ControllerAdvice + @ResponseBody
 * It applies to all @RestController classes automatically.
 * No configuration needed — Spring detects it on the classpath.
 *
 * EXCEPTION HANDLING HIERARCHY:
 *
 * AppException (our custom) ────────────────────────────────────────────────┐
 *   notFound() → 404, conflict() → 409, badRequest() → 400, etc.          │
 *                                                                           │
 * MethodArgumentNotValidException (from @Valid on request bodies) ─────────┤
 *   "email must be valid", "name must not be blank"                        │
 *   Returns: 422 with map of { fieldName: "error message" }                │
 *                                                                           │
 * HttpMessageNotReadableException (malformed JSON body) ───────────────────┤
 *   Client sends: { "name": } (syntax error in JSON)                       │
 *   Returns: 400 "Request body is malformed or missing"                    │
 *                                                                           │
 * MissingServletRequestParameterException (missing @RequestParam) ─────────┤
 *   GET /stores?lat=13.04  (missing &lng=...)                              │
 *   Returns: 400 "Required parameter 'lng' is missing"                     │
 *                                                                           │
 * MethodArgumentTypeMismatchException (wrong type in path variable) ───────┤
 *   GET /orders/abc  (orderId must be Long, "abc" is not)                  │
 *   Returns: 400 "Invalid value 'abc' for parameter 'orderId'"             │
 *                                                                           │
 * AccessDeniedException (Spring Security @PreAuthorize failed) ────────────┤
 *   @PreAuthorize("hasRole('SELLER')") — user is a BUYER                   │
 *   Returns: 403 Forbidden                                                  │
 *                                                                           │
 * BadCredentialsException (wrong password on login) ───────────────────────┤
 *   Returns: 401 Unauthorized (not 400 — the credentials were provided     │
 *   but they are incorrect, not missing)                                    │
 *                                                                           │
 * Exception (catch-all — nothing should reach here) ───────────────────────┘
 *   Any unexpected exception.
 *   Returns: 500 Internal Server Error (no internal details leaked)
 *   Logs the full stack trace for developer investigation.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── Our custom exceptions ─────────────────────────────────────────────────

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Void>> handleAppException(AppException ex) {
        log.debug("AppException: status={} errorCode={} message={}",
                ex.getStatus(), ex.getErrorCode(), ex.getMessage());

        return ResponseEntity
                .status(ex.getStatus())
                .body(ApiResponse.error(ex.getErrorCode(), ex.getMessage()));
    }

    // ── Bean Validation (@Valid on request bodies) ────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(
            MethodArgumentNotValidException ex) {

        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }

        // Example error response:
        // {
        //   "success": false,
        //   "errorCode": "VALIDATION_ERROR",
        //   "message": "Request validation failed",
        //   "data": {
        //     "email": "must be a valid email address",
        //     "name": "must not be blank",
        //     "password": "must be at least 8 characters"
        //   }
        // }

        log.debug("Validation errors: {}", fieldErrors);
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.error("VALIDATION_ERROR",
                        "Request validation failed", fieldErrors));
    }

    // ── Malformed JSON body ───────────────────────────────────────────────────

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMalformedJson(
            HttpMessageNotReadableException ex) {

        log.debug("Malformed request body: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("MALFORMED_REQUEST",
                        "Request body is malformed or missing"));
    }

    // ── Missing @RequestParam ─────────────────────────────────────────────────

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(
            MissingServletRequestParameterException ex) {

        String message = String.format("Required parameter '%s' is missing",
                ex.getParameterName());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("MISSING_PARAMETER", message));
    }

    // ── Wrong type in path variable or @RequestParam ──────────────────────────

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {

        String message = String.format(
                "Invalid value '%s' for parameter '%s'",
                ex.getValue(), ex.getName());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("TYPE_MISMATCH", message));
    }

    // ── Spring Security: @PreAuthorize failed ────────────────────────────────

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(
            AccessDeniedException ex) {

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("FORBIDDEN",
                        "You do not have permission to perform this action"));
    }

    // ── Wrong credentials on login ────────────────────────────────────────────

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials(
            BadCredentialsException ex) {

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("INVALID_CREDENTIALS",
                        "Email or password is incorrect"));
    }

    // ── Catch-all: nothing should reach here ──────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex) {

        // Log the FULL exception with stack trace.
        // Developer sees: exactly which line threw, what the state was.
        // Client sees: nothing about internals.
        log.error("Unhandled exception: {}", ex.getMessage(), ex);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR",
                        "An unexpected error occurred. Please try again."));
    }
}
