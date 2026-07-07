package com.cityapp.gateway.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Fallback responses when downstream services are unavailable.
 * Called by Circuit Breaker when a service is OPEN.
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @RequestMapping("/auth")
    public ResponseEntity<Map<String, Object>> authFallback() {
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "success", false,
                        "error", Map.of(
                                "code", "AUTH_SERVICE_UNAVAILABLE",
                                "message", "Authentication service is temporarily unavailable. " +
                                        "Please try again in a few minutes."
                        )
                ));
    }

    @RequestMapping("/notification")
    public ResponseEntity<Map<String, Object>> notificationFallback() {
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "success", false,
                        "error", Map.of(
                                "code", "NOTIFICATION_SERVICE_UNAVAILABLE",
                                "message", "Notification service is temporarily unavailable. " +
                                        "Your actions are saved and notifications " +
                                        "will be delivered when service resumes."
                        )
                ));
    }

    @RequestMapping("/main")
    public ResponseEntity<Map<String, Object>> mainFallback() {
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "success", false,
                        "error", Map.of(
                                "code", "SERVICE_UNAVAILABLE",
                                "message", "Service is temporarily unavailable. " +
                                        "We are working to restore it. Please try again shortly."
                        )
                ));
    }
}