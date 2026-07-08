package com.orderplatform.order.outbox;

import com.couchbase.client.java.Collection;
import com.couchbase.client.java.transactions.TransactionAttemptContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Stages an outbox event inside an in-flight Couchbase transaction. Must be
 * called with the same TransactionAttemptContext/Collection the caller is
 * already using for its own document writes, so the outbox record commits
 * atomically with whatever domain change it describes.
 */
@Component
public class OutboxWriter {

    private final ObjectMapper objectMapper;

    public OutboxWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(
            TransactionAttemptContext ctx,
            Collection collection,
            String topic,
            String key,
            String eventType,
            Object payload
    ) {
        Map<String, Object> payloadMap = objectMapper.convertValue(payload, new TypeReference<Map<String, Object>>() {
        });
        UUID id = UUID.randomUUID();
        OutboxRecord record = new OutboxRecord(id, topic, key, eventType, payloadMap, Instant.now(), false);
        ctx.insert(collection, "outbox::" + id, record);
    }
}
