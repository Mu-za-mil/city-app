package com.cityapp.outbox.service;

import com.cityapp.outbox.entity.OutboxEvent;
import com.cityapp.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxEventRepositoryAdapter {

    private final OutboxEventRepository repository;

    public OutboxEvent save(OutboxEvent event) {
        return repository.save(event);
    }
}
