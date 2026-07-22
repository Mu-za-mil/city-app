package com.cityapp.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Global JWT validation filter for the API Gateway.
 *
 * WHY VALIDATE JWT AT THE GATEWAY:
 *   Without gateway validation: every microservice validates the JWT itself.
 *   Each service: imports JWT library, reads the secret, validates.
 *   Duplicated code across all services.
 *   If JWT secret changes: update all services.
 *
 *   With gateway validation:
 *   JWT validated ONCE at the gateway.
 *   Downstream services: trust the X-Authenticated-User header.
 *   No JWT library needed in auth-service, order-service, etc.
 *   Services focus on business logic. Gateway handles security.
 *
 * WHAT THE GATEWAY DOES:
 *   1. Extracts Bearer token from Authorization header.
 *   2. Validates signature (JWT_SECRET must match the one in auth-service).
 *   3. Checks expiry.
 *   4. Extracts userId, email, role from claims.
 *   5. Adds headers to the forwarded request:
 *      X-User-Id: 42
 *      X-User-Email: ravi@test.com
 *      X-User-Role: USER
 *   6. Downstream service reads these headers — no JWT parsing needed.
 *
 * OPEN ENDPOINTS:
 *   /auth/register, /auth/login, /auth/refresh: no JWT required.
 *   All others: JWT required.
 *
 * REACTIVE:
 *   Spring Cloud Gateway is built on WebFlux (reactive).
 *   Filters must be non-blocking: return Mono<Void> not void.
 *   This is different from the servlet-based RateLimitFilter in the monolith.
 */
@Slf4j
@Component
public class JwtGatewayFilter implements GlobalFilter, Ordered {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    // Paths that don't require authentication
    private static final List<String> OPEN_PATHS = List.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh",
            "/api/v1/auth/otp",
            "/actuator"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange,
                             GatewayFilterChain chain) {

        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        log.info(">>> JwtGatewayFilter: path={}, authHeader={}", path, request.getHeaders().getFirst("Authorization"));

        // Skip JWT validation for open paths
        if (isOpenPath(path)) {
            return chain.filter(exchange);
        }

        // Extract token
        String authHeader = request.getHeaders()
                .getFirst("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return reject(exchange, HttpStatus.UNAUTHORIZED,
                    "Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7);

        log.info(">>> JwtGatewayFilter: path={}, authHeader={}", path, authHeader);

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // Add user context headers for downstream services (be defensive: claims may be absent)
            Object uidObj = claims.get("userId");
            String userId = uidObj != null ? uidObj.toString() : "";
            Object roleObj = claims.get("role");
            String userRole = roleObj != null ? roleObj.toString() : "";

            var requestBuilder = request.mutate();
            // Always include email (subject)
            requestBuilder.header("X-User-Email", claims.getSubject());
            if (!userId.isEmpty()) {
                requestBuilder.header("X-User-Id", userId);
            }
            if (!userRole.isEmpty()) {
                requestBuilder.header("X-User-Role", userRole);
            }

            ServerHttpRequest mutatedRequest = requestBuilder.build();

            log.debug("JWT validated: user={} path={}",
                    claims.getSubject(), path);

            return chain.filter(exchange.mutate()
                    .request(mutatedRequest)
                    .build());

        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "Token expired");
        } catch (Exception e) {
            log.warn("JWT validation failed for path {}: {}", path, e.getMessage());
            return reject(exchange, HttpStatus.UNAUTHORIZED, "Invalid token");
        }
    }

    private boolean isOpenPath(String path) {
        return OPEN_PATHS.stream().anyMatch(path::startsWith);
    }

    private Mono<Void> reject(ServerWebExchange exchange,
                              HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().add("Content-Type", "application/json");

        String body = """
                {"success":false,"error":{"code":"%s","message":"%s"}}
                """.formatted(status.name(), message);

        var buffer = response.bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));

        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -1; // Run before other filters (highest priority)
    }
}
