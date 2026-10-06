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
        try {
            repository.save(OutboxEvent.builder()
                    .eventId(UUID.randomUUID().toString())
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
