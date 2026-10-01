package com.cityapp.auth.config;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import com.cityapp.security.filter.JwtAuthFilter;
import com.cityapp.security.service.JwtService;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@Import({JwtAuthFilter.class, JwtService.class})
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final com.cityapp.auth.service.UserService userService;
    private final PasswordEncoder passwordEncoder;




    // ── Authentication Provider ────────────────────────────────────────────────

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userService);
        // Tells Spring Security: load users from our UserService.loadUserByUsername()
        provider.setPasswordEncoder(passwordEncoder);
        // Tells Spring Security: compare passwords using BCrypt.
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    // ── Security Filter Chain ─────────────────────────────────────────────────

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {

        http
                // ── CSRF ──────────────────────────────────────────────────────────
                .csrf(AbstractHttpConfigurer::disable)
                // Disabled: we use JWT in Authorization header (not cookies).
                // CSRF protects against cookie theft. No cookies = CSRF not applicable.

                // ── CORS ──────────────────────────────────────────────────────────
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // ── Session Management ────────────────────────────────────────────
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // STATELESS: No server-side session. Every request authenticated via JWT.
                // Horizontal scaling: no session affinity needed.

                // ── URL Authorization ─────────────────────────────────────────────
                .authorizeHttpRequests(auth -> auth

                                // ── PUBLIC endpoints (no JWT required) ────────────────────────
                                .requestMatchers(
                                        // Note: /api/v1/auth/** is now handled by auth-service.
                                        // These rules are kept as fallback (direct calls, bypassing gateway).
                                        "/api/v1/auth/register",
                                        "/api/v1/auth/login",
                                        "/api/v1/webhooks/**",
                                        "/api/v1/payments/webhooks/**",
                                        "/ws/**"
                                        // WHY /auth/refresh is public:
                                        // When access token expires: client cannot send valid JWT.
                                        // The refresh endpoint authenticates via refresh token in body.
                                        // If protected by JWT: cannot refresh (chicken-and-egg).
                                ).permitAll()

                                // Payment webhooks: authenticated by Razorpay's HMAC signature, not JWT
                                .requestMatchers("/api/v1/payments/webhooks/**").permitAll()

                                // Public browsing: anyone can see stores and products
                                .requestMatchers(HttpMethod.GET,
                                        "/api/v1/stores/**",
                                        "/api/v1/products/**",
                                        "/api/v1/categories/**",
                                        "/api/v1/search/**"
                                ).permitAll()

                                // Health checks: Kubernetes probes, monitoring systems
                                .requestMatchers(
                                        "/actuator/health/**",
                                        "/actuator/info",
                                        "/actuator/prometheus",
                                        "/actuator/metrics/**",
                                        "/actuator/circuitbreakers").permitAll()

                                // Swagger/OpenAPI (dev only — restrict in production)
                                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()

                                // ── PROTECTED endpoints (JWT required) ────────────────────────
                                .anyRequest().authenticated()
                        // Catch-all: any endpoint not listed above requires authentication.
                        // If you miss securing a new endpoint: it defaults to requiring auth.
                        // Fail-secure: undeclared = protected.
                )

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(
                                (request, response, authException) -> {
                                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                                    response.setContentType("application/json");
                                    response.getWriter().write("""
                        {
                            "success": false,
                            "errorCode": "UNAUTHORIZED",
                            "message": "Authentication required"
                        }
                        """);
                                }
                        )
                )

                // ── Authentication Provider ────────────────────────────────────────
                .authenticationProvider(authenticationProvider())

                // ── JWT Filter ────────────────────────────────────────────────────
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        // WHY addFilterBefore:
        // UsernamePasswordAuthenticationFilter: handles form-based login.
        // We don't use form login (we use JWT).
        // Our JwtAuthFilter must run BEFORE it to set the SecurityContext.
        // Spring Security processes filters in order.
        // JwtAuthFilter runs: sets authentication in SecurityContext.
        // UsernamePasswordAuthenticationFilter runs: sees authentication is set, skips.

        return http.build();
    }

    // ── CORS Configuration ────────────────────────────────────────────────────

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // WHY configure CORS:
        // Browsers block cross-origin requests by default.
        // Frontend at http://localhost:3000 calling API at http://localhost:8080:
        // Without CORS: browser blocks the request before it even leaves.
        // With CORS: server tells browser "this origin is allowed".

        config.setAllowedOriginPatterns(List.of("*"));
        // In production: replace * with specific origins:
        // List.of("https://app.cityapp.com", "https://admin.cityapp.com")

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);  // browser caches preflight response for 1 hour

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
