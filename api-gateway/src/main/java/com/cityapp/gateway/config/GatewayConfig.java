package com.cityapp.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

@Configuration
public class GatewayConfig {

    /**
     * The auth route is rate-limited before a trusted authenticated identity is
     * available. Never use client-supplied identity headers as a rate-limit key:
     * clients can spoof them. Use the request peer IP and fall back safely when
     * the exchange has no resolved remote address.
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
            if (remoteAddress == null || remoteAddress.getAddress() == null) {
                return Mono.just("ip:unknown");
            }

            return Mono.just("ip:" + remoteAddress.getAddress().getHostAddress());
        };
    }
}
