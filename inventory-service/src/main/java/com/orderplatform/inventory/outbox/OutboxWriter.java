package com.orderplatform.inventory.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Records an event to be published later instead of sending it to Kafka
 * directly. Call this only from within a caller's existing @Transactional
 * method, so the row commits atomically with whatever domain change (e.g. a
 * stock decrement) it describes - that atomicity is the entire point of the
 * outbox pattern.
 */
@Component
public class OutboxWriter {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void write(String topic, String key, String eventType, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            repository.save(new OutboxEvent(UUID.randomUUID(), topic, key, eventType, json, Instant.now()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload for topic " + topic, e);
        }
    }
}
