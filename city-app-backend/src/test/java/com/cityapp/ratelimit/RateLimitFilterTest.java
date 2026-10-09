package com.cityapp.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.BucketConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RateLimitFilterTest {

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        Supplier<BucketConfiguration> noConfig = () -> null;
        filter = new RateLimitFilter(
                null,
                new ObjectMapper(),
                noConfig,
                noConfig,
                noConfig,
                noConfig);
    }

    @Test
    void ignoresForwardedForWhenDirectPeerIsPublic() {
        MockHttpServletRequest request = requestFrom("203.0.113.10");
        request.addHeader("X-Forwarded-For", "198.51.100.20");

        assertEquals("203.0.113.10", filter.extractClientIp(request));
    }

    @Test
    void usesForwardedForWhenDirectPeerIsPrivateProxy() {
        MockHttpServletRequest request = requestFrom("10.2.3.4");
        request.addHeader("X-Forwarded-For", "198.51.100.20, 10.2.3.4");

        assertEquals("198.51.100.20", filter.extractClientIp(request));
    }

    @Test
    void recognizesFullRfc1918172RangeOnly() {
        MockHttpServletRequest trustedProxy = requestFrom("172.31.5.8");
        trustedProxy.addHeader("X-Forwarded-For", "198.51.100.30");
        assertEquals("198.51.100.30", filter.extractClientIp(trustedProxy));

        MockHttpServletRequest untrustedPeer = requestFrom("172.32.5.8");
        untrustedPeer.addHeader("X-Forwarded-For", "198.51.100.30");
        assertEquals("172.32.5.8", filter.extractClientIp(untrustedPeer));
    }

    @Test
    void ignoresBlankFirstForwardedForEntry() {
        MockHttpServletRequest request = requestFrom("10.0.0.5");
        request.addHeader("X-Forwarded-For", " , 198.51.100.50");

        assertEquals("10.0.0.5", filter.extractClientIp(request));
    }

    private MockHttpServletRequest requestFrom(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }
}
