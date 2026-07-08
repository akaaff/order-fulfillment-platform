package com.orderplatform.order.search;

import com.orderplatform.events.EventTopics;
import com.orderplatform.events.OrderCancelled;
import com.orderplatform.events.OrderConfirmed;
import com.orderplatform.events.OrderCreated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Keeps the "orders" Elasticsearch index in sync with order lifecycle
 * events. Runs in its own consumer group ("order-service-search-indexer")
 * separate from InventoryOutcomeListener's "order-service" group, since this
 * is a second, independent subscriber to order.confirmed/order.cancelled -
 * order-service both produces those events (via its outbox) and consumes
 * them here to update this read model.
 *
 * <p>order.created always precedes order.confirmed/order.cancelled for the
 * same orderId in practice (the latter only exist because inventory-service
 * processed the former, a round trip through two more services), so a
 * partial update targeting a not-yet-indexed document is not expected in
 * normal operation; if it ever happened, Spring Kafka's default error
 * handling retries a few times and then logs and moves on rather than
 * blocking this consumer.
 */
@Component
public class OrderSearchIndexer {

    private static final Logger log = LoggerFactory.getLogger(OrderSearchIndexer.class);
    private static final String GROUP_ID = "order-service-search-indexer";

    private final ElasticsearchOperations operations;

    public OrderSearchIndexer(ElasticsearchOperations operations) {
        this.operations = operations;
    }

    @KafkaListener(topics = EventTopics.ORDER_CREATED, groupId = GROUP_ID)
    public void onOrderCreated(OrderCreated event) {
        operations.save(OrderSearchDocument.fromCreated(event));
        log.debug("Indexed new order {} for search", event.orderId());
    }

    @KafkaListener(topics = EventTopics.ORDER_CONFIRMED, groupId = GROUP_ID)
    public void onOrderConfirmed(OrderConfirmed event) {
        updateStatus(event.orderId().toString(), "CONFIRMED", null, event.occurredAt());
    }

    @KafkaListener(topics = EventTopics.ORDER_CANCELLED, groupId = GROUP_ID)
    public void onOrderCancelled(OrderCancelled event) {
        updateStatus(event.orderId().toString(), "CANCELLED", event.reason(), event.occurredAt());
    }

    private void updateStatus(String orderId, String status, String cancellationReason, Instant updatedAt) {
        Document doc = Document.create();
        doc.put("status", status);
        // Truncate to millis - Instant.toString() emits nanosecond precision
        // when present, which Spring Data Elasticsearch's own entity mapper
        // never produces and can't parse back on read (this broke search
        // with a ConversionException on updatedAt until it was truncated).
        doc.put("updatedAt", updatedAt.truncatedTo(ChronoUnit.MILLIS).toString());
        if (cancellationReason != null) {
            doc.put("cancellationReason", cancellationReason);
        }

        UpdateQuery updateQuery = UpdateQuery.builder(orderId).withDocument(doc).build();
        operations.update(updateQuery, operations.getIndexCoordinatesFor(OrderSearchDocument.class));
        log.debug("Updated search index for order {}: status={}", orderId, status);
    }
}
