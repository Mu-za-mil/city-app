package com.cityapp.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GatewayConfigTest {

    private final GatewayConfig gatewayConfig = new GatewayConfig();

    @Test
    void ignoresClientSuppliedUserIdWhenResolvingRateLimitKey() {
        var request = MockServerHttpRequest.get("/api/v1/auth/login")
                .header("X-User-Id", "victim-user")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 12345))
                .build();
        var exchange = MockServerWebExchange.from(request);

        String key = gatewayConfig.ipKeyResolver().resolve(exchange).block();

        assertEquals("ip:127.0.0.1", key);
    }

    @Test
    void usesSafeFallbackWhenRemoteAddressIsUnavailable() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/auth/login").build());

        String key = gatewayConfig.ipKeyResolver().resolve(exchange).block();

        assertEquals("ip:unknown", key);
    }
}
