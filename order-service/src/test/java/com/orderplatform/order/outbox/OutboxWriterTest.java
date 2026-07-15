package com.orderplatform.order.outbox;

import com.couchbase.client.java.Collection;
import com.couchbase.client.java.transactions.TransactionAttemptContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link OutboxWriter}. The real ObjectMapper is used (not mocked)
 * since convertValue on a simple record is pure, deterministic behavior we want
 * to exercise for real rather than stub away. TransactionAttemptContext and
 * Collection are plain Couchbase SDK classes (non-final, package-private
 * constructors) so plain Mockito.mock works with no special extension.
 */
@ExtendWith(MockitoExtension.class)
class OutboxWriterTest {

    @Mock
    private TransactionAttemptContext ctx;

    @Mock
    private Collection collection;

    private final OutboxWriter writer = new OutboxWriter(new ObjectMapper());

    @Test
    void writeInsertsAnOutboxRecordKeyedWithTheOutboxPrefix() {
        record SamplePayload(String sku, int quantity) {
        }

        writer.write(ctx, collection, "order.created", "cust-001", "OrderCreated", new SamplePayload("sku-1", 3));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<OutboxRecord> recordCaptor = ArgumentCaptor.forClass(OutboxRecord.class);
        verify(ctx).insert(eq(collection), keyCaptor.capture(), recordCaptor.capture());

        // Key must be "outbox::<generated id>" - and that id must match the record's own id field.
        OutboxRecord inserted = recordCaptor.getValue();
        assertThat(keyCaptor.getValue()).isEqualTo("outbox::" + inserted.id());

        assertThat(inserted.topic()).isEqualTo("order.created");
        assertThat(inserted.key()).isEqualTo("cust-001");
        assertThat(inserted.eventType()).isEqualTo("OrderCreated");
        assertThat(inserted.published()).isFalse();
        assertThat(inserted.createdAt()).isNotNull();
        assertThat(inserted.id()).isNotNull();

        // Payload is stored as a generic Map (via ObjectMapper.convertValue), not the concrete record type.
        assertThat(inserted.payload())
                .isEqualTo(Map.of("sku", "sku-1", "quantity", 3));
    }

    @Test
    void writeGeneratesADifferentIdOnEachCall() {
        writer.write(ctx, collection, "order.created", "k1", "OrderCreated", Map.of("a", 1));
        writer.write(ctx, collection, "order.created", "k2", "OrderCreated", Map.of("a", 2));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(ctx, org.mockito.Mockito.times(2)).insert(eq(collection), keyCaptor.capture(), org.mockito.ArgumentMatchers.any());

        assertThat(keyCaptor.getAllValues()).doesNotHaveDuplicates();
    }
}
