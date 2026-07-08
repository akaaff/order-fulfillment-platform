package com.orderplatform.inventory.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderplatform.events.InventoryRejected;
import com.orderplatform.events.InventoryReserved;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Relays a single outbox row to Kafka. Split out from OutboxPoller (rather
 * than having the scheduled loop call this method on itself) because
 * Spring's @Transactional only takes effect through the proxy that wraps
 * calls between different beans - a self-invocation from within the same
 * class would silently skip the transaction and break the pessimistic lock
 * in findByIdForUpdate.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OutboxRelay(OutboxEventRepository repository, KafkaTemplate<String, Object> kafkaTemplate, ObjectMapper objectMapper) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void relayOne(UUID id) {
        OutboxEvent event = repository.findByIdForUpdate(id).orElse(null);
        if (event == null || event.isPublished()) {
            return;
        }

        Object payload = deserialize(event);
        try {
            kafkaTemplate.send(event.getTopic(), event.getEventKey(), payload).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish outbox event " + id, e);
        }
        event.markPublished();
        log.debug("Relayed outbox event {} ({}) to topic {}", id, event.getEventType(), event.getTopic());
    }

    private Object deserialize(OutboxEvent event) {
        Class<?> type = switch (event.getEventType()) {
            case "InventoryReserved" -> InventoryReserved.class;
            case "InventoryRejected" -> InventoryRejected.class;
            default -> throw new IllegalStateException("Unknown outbox event type: " + event.getEventType());
        };
        try {
            return objectMapper.readValue(event.getPayload(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize outbox payload for event " + event.getId(), e);
        }
    }
}
