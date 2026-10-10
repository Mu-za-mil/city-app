package com.cityapp.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ActuatorExposureConfigTest {

    @Test
    void exposesOnlyIntendedGatewayManagementEndpoints() throws IOException {
        PropertySource<?> properties = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"))
                .get(0);

        String configured = (String) properties.getProperty(
                "management.endpoints.web.exposure.include");

        assertNotNull(configured);
        List<String> exposed = Arrays.stream(configured.split(","))
                .map(String::trim)
                .toList();

        assertEquals(List.of("health", "info", "prometheus"), exposed);
        assertFalse(exposed.contains("gateway"),
                "Gateway route inspection must not be exposed over HTTP");
    }
}
