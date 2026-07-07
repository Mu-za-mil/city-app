package com.cityapp.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@Configuration
public class GatewayConfig {

    /**
     * Rate limit key: use X-User-Id if present (authenticated user),
     * else use IP address (unauthenticated request).
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            // Try authenticated user ID first
            String userId = exchange.getRequest()
                    .getHeaders()
                    .getFirst("X-User-Id");

            if (userId != null) {
                return Mono.just("user:" + userId);
            }

            // Fall back to IP address
            return Mono.just("ip:" +
                    exchange.getRequest()
                            .getRemoteAddress()
                            .getAddress()
                            .getHostAddress());
        };
    }
}
