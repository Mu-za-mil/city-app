package com.cityapp.payment.repository;

import com.cityapp.payment.entity.PaymentWebhookEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false"
})
@ContextConfiguration(classes = PaymentWebhookEventRepositoryIntegrationTest.JpaTestConfiguration.class)
@Testcontainers
class PaymentWebhookEventRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private PaymentWebhookEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    }

    @BeforeEach
    void createWebhookEventTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS payment_webhook_events");
        jdbcTemplate.execute("""
                CREATE TABLE payment_webhook_events (
                    id BIGSERIAL PRIMARY KEY,
                    event_id VARCHAR(100) NOT NULL UNIQUE,
                    event_type VARCHAR(100) NOT NULL,
                    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    @Test
    void insertIfAbsent_shouldPersistEventInPostgres() {
        int inserted = repository.insertIfAbsent("evt_postgres_1", "payment.captured");

        assertEquals(1, inserted);

        List<PaymentWebhookEvent> events = repository.findAll();
        assertEquals(1, events.size());
        assertEquals("evt_postgres_1", events.get(0).getEventId());
        assertEquals("payment.captured", events.get(0).getEventType());
        org.junit.jupiter.api.Assertions.assertNotNull(events.get(0).getReceivedAt());
    }

    @Test
    void insertIfAbsent_shouldIgnoreDuplicateEventIdAndPreserveOriginalEvent() {
        assertEquals(1, repository.insertIfAbsent("evt_duplicate", "payment.captured"));

        int duplicateInserted = repository.insertIfAbsent("evt_duplicate", "payment.failed");

        assertEquals(0, duplicateInserted);
        List<PaymentWebhookEvent> events = repository.findAll();
        assertEquals(1, events.size());
        assertEquals("payment.captured", events.get(0).getEventType());
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = PaymentWebhookEvent.class)
    @EnableJpaRepositories(
            basePackageClasses = PaymentWebhookEventRepository.class,
            excludeFilters = @Filter(
                    type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                    classes = PaymentRepository.class))
    static class JpaTestConfiguration {
    }
}
