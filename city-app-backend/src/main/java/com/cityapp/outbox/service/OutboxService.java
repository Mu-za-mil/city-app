package com.cityapp.outbox.service;

import com.cityapp.outbox.entity.OutboxEvent;
import com.cityapp.outbox.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
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

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String topic, String messageKey, Object event) {
        enqueue(topic, messageKey, event, UUID.randomUUID().toString());
    }

    /**
     * Enqueues an event with a caller-provided ID so the ID persisted in the
     * outbox matches the ID inside the serialized event payload.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String topic, String messageKey, Object event, String eventId) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }

        try {
            repository.save(OutboxEvent.builder()
                    .eventId(eventId)
                    .topic(topic)
                    .messageKey(messageKey)
                    .eventType(event.getClass().getName())
                    .payload(objectMapper.writeValueAsString(event))
                    .availableAt(Instant.now())
                    .build());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox event", e);
        }
    }
}
