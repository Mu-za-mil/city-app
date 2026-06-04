package com.cityapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * MINIMAL Security Configuration for Phase 3.
 * Enough to allow registration to work.
 *
 * Later WILL:
 *   - Add JWT filter (JwtAuthFilter)
 *   - Lock down all non-public endpoints
 *   - Add @AuthenticationPrincipal support
 *   - Add CORS configuration
 *
 * FOR NOW:
 *   - All endpoints are open (permitAll)
 *   - BCrypt bean is available (needed by UserService)
 *   - Stateless sessions (no session cookies)
 *
 * WHY BCryptPasswordEncoder(12):
 *   BCrypt is an adaptive hashing function designed for passwords.
 *   The cost factor (12) controls how slow the hash is.
 *   At cost 12: ~250ms per hash.
 *
 *   WHY SLOW IS GOOD:
 *   Fast hashes (MD5, SHA1, SHA256): GPU can try 1 billion hashes/second.
 *   At cost 12: GPU can try ~4 hashes/second per core.
 *   Brute-forcing a 10-character password: 3.4 × 10^16 attempts.
 *   At 4/sec: 2.7 × 10^8 years.
 *   At 1 billion/sec (MD5): 1.1 years.
 *
 *   The 250ms is not a bug. It is the entire security model.
 *   For user login (one hash per login attempt): 250ms is imperceptible.
 *   For tests (20 users created in @BeforeEach): 5 seconds of overhead.
 *   Fix for tests: @Profile("test") BCryptPasswordEncoder(4) → 4ms per hash.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
// @EnableMethodSecurity: enables @PreAuthorize, @PostAuthorize on methods.
// Without this: @PreAuthorize silently does nothing.
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                // WHY disable CSRF:
                // CSRF attacks exploit cookies (session cookies).
                // We use JWT in Authorization header — no cookies.
                // No cookies = CSRF not applicable = safe to disable.

                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // STATELESS: Spring Security never creates an HTTP session.
                // Every request is independently authenticated via JWT.
                // No memory overhead from session storage.
                // Horizontal scaling: no need for session affinity between servers.

                .authorizeHttpRequests(auth -> auth
                                .anyRequest().permitAll()
                        // TEMPORARY: allow everything for Phase 3 testing.
                        // Phase 4 will replace this with proper security rules.
                );

        return http.build();
    }
}
