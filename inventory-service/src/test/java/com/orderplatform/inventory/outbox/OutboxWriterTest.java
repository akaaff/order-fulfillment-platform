package com.orderplatform.inventory.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit tests for {@link OutboxWriter}: happy-path row shape and serialization-failure wrapping. */
@ExtendWith(MockitoExtension.class)
class OutboxWriterTest {

    @Mock
    private OutboxEventRepository repository;
    @Mock
    private ObjectMapper objectMapper;

    private OutboxWriter outboxWriter;

    private record SamplePayload(String field) {
    }

    @Test
    void writeSavesOutboxEventWithMatchingFields() throws JsonProcessingException {
        outboxWriter = new OutboxWriter(repository, objectMapper);
        SamplePayload payload = new SamplePayload("value");
        when(objectMapper.writeValueAsString(payload)).thenReturn("{\"field\":\"value\"}");

        outboxWriter.write("some.topic", "some-key", "SamplePayload", payload);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        OutboxEvent saved = captor.getValue();

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTopic()).isEqualTo("some.topic");
        assertThat(saved.getEventKey()).isEqualTo("some-key");
        assertThat(saved.getEventType()).isEqualTo("SamplePayload");
        assertThat(saved.getPayload()).isEqualTo("{\"field\":\"value\"}");
        assertThat(saved.isPublished()).isFalse();
    }

    @Test
    void serializationFailureIsWrappedAsIllegalStateExceptionMentioningTopic() throws JsonProcessingException {
        outboxWriter = new OutboxWriter(repository, objectMapper);
        SamplePayload payload = new SamplePayload("value");
        when(objectMapper.writeValueAsString(payload)).thenThrow(new JsonProcessingException("boom") {
        });

        assertThatThrownBy(() -> outboxWriter.write("some.topic", "some-key", "SamplePayload", payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("some.topic");

        verifyNoInteractions(repository);
    }
}
