package com.cityapp.outbox.service;

import com.cityapp.outbox.entity.OutboxEvent;
import com.cityapp.outbox.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * Reuses an event's own ID when it has one. This keeps the database
     * envelope and serialized event payload consistent for all callers.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String topic, String messageKey, Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            String eventId = readEventId(payload);
            if (eventId == null || eventId.isBlank()) {
                eventId = UUID.randomUUID().toString();
            }
            save(topic, messageKey, event, eventId, payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox event", e);
        }
    }

    /**
     * Enqueues an event with a caller-provided ID. If the payload itself has
     * an eventId, reject mismatches rather than storing contradictory IDs.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String topic, String messageKey, Object event, String eventId) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }

        try {
            String payload = objectMapper.writeValueAsString(event);
            String payloadEventId = readEventId(payload);
            if (payloadEventId != null && !payloadEventId.isBlank()
                    && !eventId.equals(payloadEventId)) {
                throw new IllegalArgumentException(
                        "Provided eventId does not match the event payload eventId");
            }
            save(topic, messageKey, event, eventId, payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox event", e);
        }
    }

    private String readEventId(String payload) throws JsonProcessingException {
        JsonNode eventIdNode = objectMapper.readTree(payload).get("eventId");
        return eventIdNode != null && eventIdNode.isTextual()
                ? eventIdNode.asText()
                : null;
    }

    private void save(String topic, String messageKey, Object event,
                      String eventId, String payload) {
        repository.save(OutboxEvent.builder()
                .eventId(eventId)
                .topic(topic)
                .messageKey(messageKey)
                .eventType(event.getClass().getName())
                .payload(payload)
                .availableAt(Instant.now())
                .build());
    }
}
