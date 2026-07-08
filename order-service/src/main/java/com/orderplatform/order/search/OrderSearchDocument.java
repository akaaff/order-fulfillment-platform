package com.orderplatform.order.search;

import com.orderplatform.events.OrderCreated;
import com.orderplatform.events.OrderLine;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;
import java.util.List;

/**
 * Read-model projection of an order for search/audit, kept in sync with the
 * Couchbase-backed Order aggregate by OrderSearchIndexer consuming the same
 * order lifecycle events every other consumer sees - this index is
 * eventually consistent with the source of truth, never written to directly
 * by request-handling code.
 */
@Document(indexName = "orders")
public record OrderSearchDocument(
        @Id
        String orderId,

        @Field(type = FieldType.Keyword)
        String customerId,

        @Field(type = FieldType.Keyword)
        String status,

        @Field(type = FieldType.Keyword)
        List<String> skus,

        @Field(type = FieldType.Text)
        String cancellationReason,

        @Field(type = FieldType.Date)
        Instant createdAt,

        @Field(type = FieldType.Date)
        Instant updatedAt
) {
    public static OrderSearchDocument fromCreated(OrderCreated event) {
        List<String> skus = event.lines().stream().map(OrderLine::sku).toList();
        return new OrderSearchDocument(
                event.orderId().toString(),
                event.customerId(),
                "PENDING",
                skus,
                null,
                event.occurredAt(),
                event.occurredAt()
        );
    }
}
