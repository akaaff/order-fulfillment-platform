package com.orderplatform.inventory.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderplatform.events.InventoryReserved;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit tests for {@link OutboxRelay#relayOne(UUID)}: no-op cases, deserialize/publish failure wrapping, and the happy path. */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private OutboxEventRepository repository;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock
    private ObjectMapper objectMapper;

    private OutboxRelay relay;

    private OutboxEvent unpublishedEvent(String eventType, String payload) {
        return new OutboxEvent(UUID.randomUUID(), "inventory.reserved", "order-key", eventType, payload, Instant.now());
    }

    @Test
    void notFoundIdIsNoOpAndNeverSendsToKafka() {
        relay = new OutboxRelay(repository, kafkaTemplate, objectMapper);
        UUID id = UUID.randomUUID();
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.empty());

        relay.relayOne(id);

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void alreadyPublishedEventIsNoOp() {
        relay = new OutboxRelay(repository, kafkaTemplate, objectMapper);
        OutboxEvent event = unpublishedEvent("InventoryReserved", "{}");
        event.markPublished();
        when(repository.findByIdForUpdate(event.getId())).thenReturn(Optional.of(event));

        relay.relayOne(event.getId());

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void unknownEventTypeThrowsIllegalStateException() {
        relay = new OutboxRelay(repository, kafkaTemplate, objectMapper);
        OutboxEvent event = unpublishedEvent("SomeUnknownType", "{}");
        when(repository.findByIdForUpdate(event.getId())).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> relay.relayOne(event.getId()))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void deserializeFailureIsWrappedAsIllegalStateException() throws JsonProcessingException {
        relay = new OutboxRelay(repository, kafkaTemplate, objectMapper);
        OutboxEvent event = unpublishedEvent("InventoryReserved", "not valid json");
        when(repository.findByIdForUpdate(event.getId())).thenReturn(Optional.of(event));
        when(objectMapper.readValue(anyString(), eq(InventoryReserved.class)))
                .thenThrow(new JsonProcessingException("boom") {
                });

        assertThatThrownBy(() -> relay.relayOne(event.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(event.getId().toString());

        verifyNoInteractions(kafkaTemplate);
    }

    @SuppressWarnings("unchecked")
    @Test
    void kafkaSendFailureIsWrappedAsIllegalStateExceptionMentioningEventId() throws Exception {
        relay = new OutboxRelay(repository, kafkaTemplate, objectMapper);
        InventoryReserved payload = new InventoryReserved(UUID.randomUUID(), Instant.now(), UUID.randomUUID());
        OutboxEvent event = unpublishedEvent("InventoryReserved", "{\"irrelevant\":true}");
        when(repository.findByIdForUpdate(event.getId())).thenReturn(Optional.of(event));
        when(objectMapper.readValue(anyString(), eq(InventoryReserved.class))).thenReturn(payload);

        CompletableFuture<SendResult<String, Object>> failedFuture = mock(CompletableFuture.class);
        when(failedFuture.get(5, java.util.concurrent.TimeUnit.SECONDS))
                .thenThrow(new ExecutionException("kafka down", new RuntimeException("kafka down")));
        when(kafkaTemplate.send(event.getTopic(), event.getEventKey(), payload)).thenReturn(failedFuture);

        assertThatThrownBy(() -> relay.relayOne(event.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(event.getId().toString());
    }

    @Test
    void happyPathSendsToKafkaWithCorrectTopicAndKeyThenMarksPublished() throws Exception {
        relay = new OutboxRelay(repository, kafkaTemplate, objectMapper);
        InventoryReserved payload = new InventoryReserved(UUID.randomUUID(), Instant.now(), UUID.randomUUID());
        OutboxEvent event = unpublishedEvent("InventoryReserved", "{\"irrelevant\":true}");
        when(repository.findByIdForUpdate(event.getId())).thenReturn(Optional.of(event));
        when(objectMapper.readValue(anyString(), eq(InventoryReserved.class))).thenReturn(payload);

        CompletableFuture<SendResult<String, Object>> successFuture = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(event.getTopic(), event.getEventKey(), payload)).thenReturn(successFuture);

        relay.relayOne(event.getId());

        verify(kafkaTemplate, times(1)).send(event.getTopic(), event.getEventKey(), payload);
        org.assertj.core.api.Assertions.assertThat(event.isPublished()).isTrue();
    }
}
