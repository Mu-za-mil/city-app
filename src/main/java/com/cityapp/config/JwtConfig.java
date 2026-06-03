package com.cityapp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Validates critical configuration at startup.
 * Fails fast rather than allowing misconfigured deployment.
 *
 * WHY @EventListener(ApplicationReadyEvent.class):
 *   @PostConstruct runs when the bean is created.
 *   The @Value may not be resolved yet at that point.
 *   ApplicationReadyEvent fires after ALL beans are configured.
 *   Safe to validate @Value fields here.
 *
 * WHY VALIDATE JWT_SECRET:
 *   Scenario: Developer copies .env.example to .env and forgets to change JWT_SECRET.
 *   Without validation: app starts with a publicly known secret.
 *   Anyone can forge valid JWTs. Complete authentication bypass.
 *   With validation: app refuses to start. Error forces the developer to fix it.
 *   A startup failure caught on a developer's machine is infinitely better
 *   than a security breach in production.
 */
@Slf4j
@Component
public class JwtConfig {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @EventListener(ApplicationReadyEvent.class)
    public void validateJwtSecret() {

        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "STARTUP FAILED: JWT_SECRET is not configured. " +
                            "Generate one with: openssl rand -hex 32 " +
                            "Then add it to your .env file.");
        }

        if (jwtSecret.length() < 32) {
            throw new IllegalStateException(
                    "STARTUP FAILED: JWT_SECRET must be at least 32 characters. " +
                            "Current length: " + jwtSecret.length() + " characters. " +
                            "Generate a proper secret: openssl rand -hex 32");
        }

        // Warn if using known placeholder values
        if (jwtSecret.contains("placeholder") ||
                jwtSecret.contains("example") ||
                jwtSecret.contains("REPLACE")) {
            log.warn("═════════════════════════════════════════════════════");
            log.warn("⚠ WARNING: JWT_SECRET appears to be a placeholder!");
            log.warn("  Replace it before deploying to production.");
            log.warn("  Generate: openssl rand -hex 32");
            log.warn("═════════════════════════════════════════════════════");
        }

        log.info("✅ JWT secret validated (length: {} characters)", jwtSecret.length());
    }
}