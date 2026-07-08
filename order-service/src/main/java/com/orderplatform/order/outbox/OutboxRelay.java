package com.orderplatform.order.outbox;

import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.kv.GetResult;
import com.couchbase.client.java.kv.ReplaceOptions;
import com.couchbase.client.java.query.QueryResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderplatform.events.OrderCancelled;
import com.orderplatform.events.OrderConfirmed;
import com.orderplatform.events.OrderCreated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Polls for outbox documents not yet published and relays them to Kafka one
 * at a time. Each document is re-fetched (for a fresh CAS) immediately before
 * publishing, and only marked published - via a CAS-guarded replace - after
 * the Kafka send is acknowledged. If the send fails, the document is left
 * unpublished and retried on the next poll rather than silently dropped.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final Cluster cluster;
    private final Collection collection;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String bucketName;

    public OutboxRelay(
            Cluster cluster,
            Collection collection,
            KafkaTemplate<String, Object> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${orderplatform.couchbase.bucket}") String bucketName
    ) {
        this.cluster = cluster;
        this.collection = collection;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.bucketName = bucketName;
    }

    @Scheduled(fixedDelay = 1000)
    public void relay() {
        QueryResult result = cluster.query(
                "SELECT META(o).id AS id FROM `" + bucketName + "` o "
                        + "WHERE META(o).id LIKE 'outbox::%' AND o.published = false"
        );
        List<JsonObject> rows = result.rowsAsObject();
        for (JsonObject row : rows) {
            relayOne(row.getString("id"));
        }
    }

    private void relayOne(String id) {
        GetResult getResult;
        try {
            getResult = collection.get(id);
        } catch (DocumentNotFoundException e) {
            return;
        }

        OutboxRecord record = getResult.contentAs(OutboxRecord.class);
        if (record.published()) {
            return;
        }

        Object payload = deserialize(record);
        try {
            kafkaTemplate.send(record.topic(), record.key(), payload).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish outbox event " + id, e);
        }

        OutboxRecord published = new OutboxRecord(
                record.id(), record.topic(), record.key(), record.eventType(), record.payload(), record.createdAt(), true
        );
        collection.replace(id, published, ReplaceOptions.replaceOptions().cas(getResult.cas()));
        log.debug("Relayed outbox event {} ({}) to topic {}", id, record.eventType(), record.topic());
    }

    private Object deserialize(OutboxRecord record) {
        Class<?> type = switch (record.eventType()) {
            case "OrderCreated" -> OrderCreated.class;
            case "OrderConfirmed" -> OrderConfirmed.class;
            case "OrderCancelled" -> OrderCancelled.class;
            default -> throw new IllegalStateException("Unknown outbox event type: " + record.eventType());
        };
        return objectMapper.convertValue(record.payload(), type);
    }
}
