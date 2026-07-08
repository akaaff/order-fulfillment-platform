package com.orderplatform.order.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A not-yet-published (or already-published) Kafka event, stored as a
 * Couchbase document keyed "outbox::&lt;id&gt;" in the same bucket/collection
 * as the Order document it was written alongside in the same transaction.
 * payload is a generic map (rather than the concrete event type) so this one
 * record type can hold any event; eventType is the discriminator OutboxRelay
 * uses to reconstruct the concrete class before publishing.
 */
public record OutboxRecord(
        UUID id,
        String topic,
        String key,
        String eventType,
        Map<String, Object> payload,
        Instant createdAt,
        boolean published
) {
}
