package com.orderplatform.inventory.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link OutboxPoller#poll()}: relays each unpublished id, in order, and does nothing when there are none. */
@ExtendWith(MockitoExtension.class)
class OutboxPollerTest {

    @Mock
    private OutboxEventRepository repository;
    @Mock
    private OutboxRelay relay;

    private OutboxPoller poller;

    @Test
    void noUnpublishedIdsNeverCallsRelay() {
        poller = new OutboxPoller(repository, relay);
        when(repository.findUnpublishedIds()).thenReturn(List.of());

        poller.poll();

        verify(relay, never()).relayOne(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void multipleIdsAreEachRelayedExactlyOnceInOrder() {
        poller = new OutboxPoller(repository, relay);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        when(repository.findUnpublishedIds()).thenReturn(List.of(first, second, third));

        poller.poll();

        verify(relay, times(1)).relayOne(first);
        verify(relay, times(1)).relayOne(second);
        verify(relay, times(1)).relayOne(third);

        InOrder inOrder = inOrder(relay);
        inOrder.verify(relay).relayOne(first);
        inOrder.verify(relay).relayOne(second);
        inOrder.verify(relay).relayOne(third);
    }
}
