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
 * SECURITY MODEL:
 *   1. The gateway is the public entry point.
 *   2. The gateway validates the caller's JWT.
 *   3. Client-supplied identity headers are removed before forwarding.
 *   4. The gateway adds identity headers derived only from the validated JWT.
 *   5. The monolith still validates the forwarded JWT itself. It does NOT
 *      authenticate a request merely because an X-Gateway-Request header exists.
 *
 * WHY THE MONOLITH VALIDATES THE JWT TOO:
 *   Identity headers are metadata, not proof of authentication. A downstream
 *   service must never treat a client-controlled header as an authentication
 *   credential. Keeping JWT validation in the monolith makes the service safe
 *   even if it is accidentally reached through another internal path.
 *
 * OPEN ENDPOINTS:
 *   /auth/register, /auth/login, /auth/refresh: no JWT required.
 */
@Slf4j
@Component
public class JwtGatewayFilter implements GlobalFilter, Ordered {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    private static final List<String> OPEN_PATHS = List.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh",
            "/api/v1/auth/otp",
            "/actuator"
    );

    private static final List<String> TRUSTED_IDENTITY_HEADERS = List.of(
            "X-User-Email",
            "X-User-Id",
            "X-User-Role",
            "X-Gateway-Request"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange,
                             GatewayFilterChain chain) {

        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // Client-controlled identity headers are never allowed to pass through.
        // They are re-created below only after a JWT has been validated.
        ServerHttpRequest.Builder requestBuilder = request.mutate();
        requestBuilder.headers(headers ->
                TRUSTED_IDENTITY_HEADERS.forEach(headers::remove));

        ServerHttpRequest sanitizedRequest = requestBuilder.build();

        // Skip JWT validation for open paths.
        if (isOpenPath(path)) {
            return chain.filter(exchange.mutate()
                    .request(sanitizedRequest)
                    .build());
        }

        String authHeader = sanitizedRequest.getHeaders()
                .getFirst("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return reject(exchange, HttpStatus.UNAUTHORIZED,
                    "Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7);

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(Keys.hmacShaKeyFor(
                            jwtSecret.getBytes(StandardCharsets.UTF_8)))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Object uidObj = claims.get("userId");
            String userId = uidObj != null ? uidObj.toString() : "";

            Object roleObj = claims.get("role");
            String userRole = roleObj != null ? roleObj.toString() : "";

            requestBuilder.headers(headers -> {
                // Explicitly replace any existing values with values derived
                // from the validated JWT.
                headers.set("X-User-Email", claims.getSubject());

                if (!userId.isEmpty()) {
                    headers.set("X-User-Id", userId);
                }

                if (!userRole.isEmpty()) {
                    headers.set("X-User-Role", userRole);
                }
            });

            ServerHttpRequest mutatedRequest = requestBuilder.build();

            log.debug("JWT validated for path={} user={}",
                    path, claims.getSubject());

            // IMPORTANT: Authorization is intentionally preserved so the
            // downstream monolith can independently validate the JWT.
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
        return -1;
    }
}
