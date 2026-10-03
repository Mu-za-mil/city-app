package com.cityapp.gateway.filter;

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
import java.util.Set;

@Slf4j
@Component
public class JwtGatewayFilter implements GlobalFilter, Ordered {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    private static final List<String> OPEN_EXACT_PATHS = List.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh"
    );

    private static final List<String> OPEN_PREFIX_PATHS = List.of(
            "/api/v1/auth/otp/"
    );

    private static final Set<String> REMOVED_HEADERS = Set.of(
            "X-User-Email",
            "X-User-Id",
            "X-User-Role",
            "X-Gateway-Request"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange,
                             GatewayFilterChain chain) {

        String path = exchange.getRequest().getURI().getPath();

        ServerHttpRequest sanitizedRequest = exchange.getRequest().mutate()
                .headers(headers -> REMOVED_HEADERS.forEach(headers::remove))
                .build();

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
            Jwts.parser()
                    .verifyWith(Keys.hmacShaKeyFor(
                            jwtSecret.getBytes(StandardCharsets.UTF_8)))
                    .build()
                    .parseSignedClaims(token);

            log.debug("JWT validated for path={}", path);

            // Keep Authorization intact: the downstream service independently
            // validates the same JWT and establishes its SecurityContext.
            return chain.filter(exchange.mutate()
                    .request(sanitizedRequest)
                    .build());

        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "Token expired");
        } catch (Exception e) {
            log.warn("JWT validation failed for path {}: {}", path, e.getMessage());
            return reject(exchange, HttpStatus.UNAUTHORIZED, "Invalid token");
        }
    }

    private boolean isOpenPath(String path) {
        return OPEN_EXACT_PATHS.contains(path)
                || OPEN_PREFIX_PATHS.stream().anyMatch(path::startsWith);
    }

    private Mono<Void> reject(ServerWebExchange exchange,
                              HttpStatus status,
                              String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().set("Content-Type", "application/json");

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
