package com.orderplatform.inventory.outbox;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A not-yet-published (or already-published) Kafka event, written in the
 * same DB transaction as the domain change it describes. payload is stored
 * as the raw JSON for the event record; eventType is a discriminator
 * OutboxRelay uses to reconstruct the concrete event class before publishing.
 */
@Entity
public class OutboxEvent {

    @Id
    private UUID id;

    private String topic;

    private String eventKey;

    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    private Instant createdAt;

    private boolean published;

    protected OutboxEvent() {
        // JPA
    }

    public OutboxEvent(UUID id, String topic, String eventKey, String eventType, String payload, Instant createdAt) {
        this.id = id;
        this.topic = topic;
        this.eventKey = eventKey;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = createdAt;
        this.published = false;
    }

    public UUID getId() {
        return id;
    }

    public String getTopic() {
        return topic;
    }

    public String getEventKey() {
        return eventKey;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public boolean isPublished() {
        return published;
    }

    public void markPublished() {
        this.published = true;
    }
}
