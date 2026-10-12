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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false"
})
@ContextConfiguration(classes = PaymentWebhookEventRepositoryIntegrationTest.JpaTestConfiguration.class)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentWebhookEventRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private PaymentWebhookEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
        int inserted = insertEvent("evt_postgres_1", "payment.captured");

        assertEquals(1, inserted);

        List<PaymentWebhookEvent> events = repository.findAll();
        assertEquals(1, events.size());
        assertEquals("evt_postgres_1", events.get(0).getEventId());
        assertEquals("payment.captured", events.get(0).getEventType());
        org.junit.jupiter.api.Assertions.assertNotNull(events.get(0).getReceivedAt());
    }

    @Test
    void insertIfAbsent_shouldIgnoreDuplicateEventIdAndPreserveOriginalEvent() {
        assertEquals(1, insertEvent("evt_duplicate", "payment.captured"));

        int duplicateInserted = insertEvent("evt_duplicate", "payment.failed");

        assertEquals(0, duplicateInserted);
        List<PaymentWebhookEvent> events = repository.findAll();
        assertEquals(1, events.size());
        assertEquals("payment.captured", events.get(0).getEventType());
    }

    @Test
    void insertIfAbsent_shouldAllowOnlyOneInsertWhenSameEventArrivesConcurrently() throws Exception {
        int workerCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        List<Future<Integer>> results = new ArrayList<>();

        try {
            for (int i = 0; i < workerCount; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start concurrent inserts");
                    }
                    return transactionTemplate.execute(status ->
                            repository.insertIfAbsent("evt_concurrent", "payment.captured"));
                }));
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS), "Workers did not become ready in time");
            start.countDown();

            int totalInserted = 0;
            for (Future<Integer> result : results) {
                totalInserted += result.get(15, TimeUnit.SECONDS);
            }

            assertEquals(1, totalInserted, "Exactly one concurrent request should insert the event");
            assertEquals(1L, repository.count(), "Only one row should exist for the event ID");
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void insertIfAbsent_shouldRollbackEventWhenProcessingFailsAndAllowRetry() {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        assertThrows(IllegalStateException.class, () ->
                transactionTemplate.executeWithoutResult(status -> {
                    assertEquals(1, repository.insertIfAbsent("evt_retry_after_failure", "payment.captured"));
                    throw new IllegalStateException("Simulated downstream processing failure");
                }));

        assertEquals(0L, repository.count(), "The event insert should roll back with failed processing");

        assertEquals(1, insertEvent("evt_retry_after_failure", "payment.captured"),
                "A retry should be able to claim the event after rollback");
        assertEquals(1L, repository.count());
    }

    private int insertEvent(String eventId, String eventType) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        return transactionTemplate.execute(status -> repository.insertIfAbsent(eventId, eventType));
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackages = "com.cityapp")
    @EnableJpaRepositories(
            basePackageClasses = PaymentWebhookEventRepository.class,
            excludeFilters = @Filter(
                    type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                    classes = PaymentRepository.class))
    static class JpaTestConfiguration {
    }
}
