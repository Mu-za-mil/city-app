package com.cityapp.outbox.service;

import com.cityapp.outbox.entity.OutboxEvent;
import com.cityapp.outbox.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    @Mock
    private OutboxEventRepository repository;

    private ObjectMapper objectMapper;
    private OutboxService outboxService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        outboxService = new OutboxService(repository, objectMapper);
    }

    @Test
    void enqueue_shouldReuseEventIdFromPayload() throws Exception {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> event = Map.of("eventId", eventId, "orderId", 42L);

        outboxService.enqueue("order.status.changed", "42", event);

        OutboxEvent saved = captureSavedEvent();
        assertEquals(eventId, saved.getEventId());
        JsonNode payload = objectMapper.readTree(saved.getPayload());
        assertEquals(eventId, payload.get("eventId").asText());
    }

    @Test
    void enqueue_shouldGenerateEventIdWhenPayloadHasNone() throws Exception {
        Map<String, Object> event = Map.of("orderId", 42L);

        outboxService.enqueue("order.created", "42", event);

        OutboxEvent saved = captureSavedEvent();
        assertDoesNotThrow(() -> UUID.fromString(saved.getEventId()));
        assertFalse(objectMapper.readTree(saved.getPayload()).has("eventId"));
    }

    @Test
    void enqueueWithExplicitId_shouldRejectMismatchWithPayload() {
        Map<String, Object> event = Map.of("eventId", "payload-id", "orderId", 42L);

        assertThrows(IllegalArgumentException.class,
                () -> outboxService.enqueue("order.status.changed", "42", event, "row-id"));

        verify(repository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void enqueueWithExplicitId_shouldAcceptMatchingPayloadId() throws Exception {
        Map<String, Object> event = Map.of("eventId", "same-id", "orderId", 42L);

        outboxService.enqueue("order.status.changed", "42", event, "same-id");

        assertEquals("same-id", captureSavedEvent().getEventId());
    }

    private OutboxEvent captureSavedEvent() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
