package com.cityapp.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ActuatorExposureConfigTest {

    @Test
    void healthEndpointDoesNotExposeComponentDetails() throws IOException {
        PropertySource<?> properties = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .get(0);

        assertEquals("never", properties.getProperty("management.endpoint.health.show-details"));
    }

    @Test
    void environmentEndpointIsNotExposedOverHttp() throws IOException {
        PropertySource<?> properties = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .get(0);

        String exposedEndpoints = (String) properties.getProperty(
                "management.endpoints.web.exposure.include");

        assertNotNull(exposedEndpoints);
        assertFalse(exposedEndpoints.split(",").length == 0);
        assertFalse(java.util.Arrays.stream(exposedEndpoints.split(","))
                .map(String::trim)
                .anyMatch("env"::equals));
    }

    @Test
    void exposesOnlyHealthInfoAndPrometheusEndpoints() throws IOException {
        PropertySource<?> properties = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .get(0);

        String exposedEndpoints = (String) properties.getProperty(
                "management.endpoints.web.exposure.include");

        assertNotNull(exposedEndpoints);
        assertEquals(java.util.List.of("health", "info", "prometheus"),
                java.util.Arrays.stream(exposedEndpoints.split(","))
                        .map(String::trim)
                        .toList());
    }
}

