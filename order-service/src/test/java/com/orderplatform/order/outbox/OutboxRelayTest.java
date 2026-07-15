package com.orderplatform.order.outbox;

import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.kv.GetResult;
import com.couchbase.client.java.kv.ReplaceOptions;
import com.couchbase.client.java.query.QueryResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orderplatform.events.OrderCancelled;
import com.orderplatform.events.OrderConfirmed;
import com.orderplatform.events.OrderCreated;
import com.orderplatform.events.OrderLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OutboxRelay}'s per-document relay logic (the private
 * {@code relayOne} method). Since it's private, these tests drive it through the
 * public {@code @Scheduled relay()} entry point with {@code cluster.query(...)}
 * stubbed to hand back exactly one candidate document id - this avoids needing a
 * real Couchbase cluster (the N1QL query itself is integration-test territory,
 * out of scope here) while still exercising the actual get/deserialize/publish/
 * replace sequence for that one document.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private Cluster cluster;

    @Mock
    private Collection collection;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    // Real ObjectMapper (with JavaTimeModule, matching Spring Boot's autoconfigured
    // bean) rather than a mock - deserialization behavior is exactly what's under test.
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private OutboxRelay relay;

    private static final String DOC_ID = "outbox::11111111-1111-1111-1111-111111111111";

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(cluster, collection, kafkaTemplate, objectMapper, "orders");

        QueryResult queryResult = mock(QueryResult.class);
        when(queryResult.rowsAsObject()).thenReturn(List.of(JsonObject.create().put("id", DOC_ID)));
        when(cluster.query(anyString())).thenReturn(queryResult);
    }

    private Map<String, Object> toPayloadMap(Object event) {
        return objectMapper.convertValue(event, new TypeReference<Map<String, Object>>() {
        });
    }

    private GetResult stubGetResult(OutboxRecord record, long cas) {
        GetResult getResult = mock(GetResult.class);
        when(getResult.contentAs(OutboxRecord.class)).thenReturn(record);
        // cas() is only actually consumed on the happy path (right before the replace call) -
        // the already-published/unknown-type/send-failure tests return or throw before reaching
        // it, so this stub is lenient to avoid Mockito's strict-stubbing failure in those cases.
        org.mockito.Mockito.lenient().when(getResult.cas()).thenReturn(cas);
        when(collection.get(DOC_ID)).thenReturn(getResult);
        return getResult;
    }

    @Test
    void documentNotFoundReturnsSilentlyWithNoKafkaSend() {
        when(collection.get(DOC_ID)).thenThrow(new DocumentNotFoundException(null));

        relay.relay();

        verifyNoInteractions(kafkaTemplate);
        verify(collection, never()).replace(anyString(), any(), any(ReplaceOptions.class));
    }

    @Test
    void alreadyPublishedDocumentIsANoOp() {
        OutboxRecord record = new OutboxRecord(
                UUID.randomUUID(), "order.created", "cust-001", "OrderCreated",
                Map.of("x", 1), Instant.now().truncatedTo(ChronoUnit.MILLIS), true);
        stubGetResult(record, 1L);

        relay.relay();

        verifyNoInteractions(kafkaTemplate);
        verify(collection, never()).replace(anyString(), any(), any(ReplaceOptions.class));
    }

    @Test
    void unknownEventTypeThrowsIllegalStateException() {
        OutboxRecord record = new OutboxRecord(
                UUID.randomUUID(), "order.created", "cust-001", "SomeUnknownEventType",
                Map.of("x", 1), Instant.now().truncatedTo(ChronoUnit.MILLIS), false);
        stubGetResult(record, 1L);

        assertThatThrownBy(() -> relay.relay())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown outbox event type");

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void kafkaSendFailureIsWrappedAsIllegalStateExceptionAndLeavesDocumentUnpublished() {
        OrderCreated event = new OrderCreated(
                UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.MILLIS),
                UUID.randomUUID(), "cust-001", List.of(new OrderLine("sku-1", 2)));
        OutboxRecord record = new OutboxRecord(
                UUID.randomUUID(), "order.created", "cust-001", "OrderCreated",
                toPayloadMap(event), Instant.now().truncatedTo(ChronoUnit.MILLIS), false);
        stubGetResult(record, 42L);

        when(kafkaTemplate.send(eq("order.created"), eq("cust-001"), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker unreachable")));

        assertThatThrownBy(() -> relay.relay())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to publish outbox event");

        // Send was attempted but never acknowledged - document must be left unpublished (no replace call).
        verify(collection, never()).replace(anyString(), any(), any(ReplaceOptions.class));
    }

    @Test
    void happyPathOrderCreatedPublishesAndMarksReplaced() {
        OrderCreated event = new OrderCreated(
                UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.MILLIS),
                UUID.randomUUID(), "cust-001", List.of(new OrderLine("sku-1", 2)));
        assertHappyPath("order.created", "cust-001", "OrderCreated", event, event);
    }

    @Test
    void happyPathOrderConfirmedPublishesAndMarksReplaced() {
        OrderConfirmed event = new OrderConfirmed(
                UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.MILLIS), UUID.randomUUID());
        assertHappyPath("order.confirmed", "cust-002", "OrderConfirmed", event, event);
    }

    @Test
    void happyPathOrderCancelledPublishesAndMarksReplaced() {
        OrderCancelled event = new OrderCancelled(
                UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.MILLIS), UUID.randomUUID(), "out of stock");
        assertHappyPath("order.cancelled", "cust-003", "OrderCancelled", event, event);
    }

    /**
     * Shared happy-path assertion: given an already-staged outbox record for one of
     * the three event types this relay understands, relaying it must (a) publish the
     * deserialized concrete event to Kafka under the record's own topic/key, and
     * (b) mark the document published=true via a CAS-guarded replace using the exact
     * CAS value returned by the preceding get().
     */
    private void assertHappyPath(String topic, String key, String eventType, Object event, Object expectedPayload) {
        UUID recordId = UUID.randomUUID();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        OutboxRecord record = new OutboxRecord(recordId, topic, key, eventType, toPayloadMap(event), createdAt, false);
        long cas = 987654321L;
        stubGetResult(record, cas);

        when(kafkaTemplate.send(eq(topic), eq(key), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay.relay();

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(topic), eq(key), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).isEqualTo(expectedPayload);

        ArgumentCaptor<Object> replacedCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<ReplaceOptions> optionsCaptor = ArgumentCaptor.forClass(ReplaceOptions.class);
        verify(collection).replace(eq(DOC_ID), replacedCaptor.capture(), optionsCaptor.capture());

        OutboxRecord replaced = (OutboxRecord) replacedCaptor.getValue();
        assertThat(replaced.published()).isTrue();
        assertThat(replaced.id()).isEqualTo(recordId);
        assertThat(replaced.topic()).isEqualTo(topic);
        assertThat(replaced.key()).isEqualTo(key);
        assertThat(replaced.eventType()).isEqualTo(eventType);
        assertThat(replaced.createdAt()).isEqualTo(createdAt);

        // The CAS passed to ReplaceOptions must be the exact value returned by get(),
        // otherwise the replace would race against a concurrent modification undetected.
        assertThat(optionsCaptor.getValue().build().cas()).isEqualTo(cas);
    }
}
