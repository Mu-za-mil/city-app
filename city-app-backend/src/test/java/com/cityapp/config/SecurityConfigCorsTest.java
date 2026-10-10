package com.cityapp.config;

import com.cityapp.security.filter.JwtAuthFilter;
import com.cityapp.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SecurityConfigCorsTest {

    private SecurityConfig securityConfig() {
        return new SecurityConfig(
                mock(JwtAuthFilter.class),
                mock(UserService.class),
                mock(PasswordEncoder.class));
    }

    @Test
    void usesConfiguredCommaSeparatedOrigins() {
        SecurityConfig config = securityConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins",
                "https://app.cityapp.example, https://admin.cityapp.example");

        CorsConfigurationSource source = config.corsConfigurationSource();
        CorsConfiguration cors = source.getCorsConfiguration(
                new MockHttpServletRequest("GET", "/api/v1/stores"));

        assertNotNull(cors);
        assertEquals(
                java.util.List.of("https://app.cityapp.example", "https://admin.cityapp.example"),
                cors.getAllowedOrigins());
        assertTrue(cors.getAllowCredentials());
    }

    @Test
    void doesNotAllowAnUnlistedOrigin() {
        SecurityConfig config = securityConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins", "https://app.cityapp.example");

        CorsConfigurationSource source = config.corsConfigurationSource();
        CorsConfiguration cors = source.getCorsConfiguration(
                new MockHttpServletRequest("GET", "/api/v1/stores"));

        assertNotNull(cors);
        assertFalse(cors.checkOrigin("https://attacker.example") != null);
    }

    @Test
    void rejectsWildcardOriginsWhenCredentialsAreEnabled() {
        SecurityConfig config = securityConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins", "*");

        assertThrows(IllegalStateException.class, config::corsConfigurationSource);
    }
}
