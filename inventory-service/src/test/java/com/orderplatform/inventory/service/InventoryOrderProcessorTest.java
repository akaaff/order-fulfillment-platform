package com.orderplatform.inventory.service;

import com.orderplatform.events.EventTopics;
import com.orderplatform.events.OrderCreated;
import com.orderplatform.events.OrderLine;
import com.orderplatform.inventory.outbox.OutboxWriter;
import com.orderplatform.inventory.outbox.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit tests for {@link InventoryOrderProcessor}: idempotency, retry-on-contention, and outbox outcome recording. */
@ExtendWith(MockitoExtension.class)
class InventoryOrderProcessorTest {

    @Mock
    private InventoryReservationService reservationService;
    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private OutboxWriter outboxWriter;

    private InventoryOrderProcessor processor;

    private OrderCreated sampleEvent() {
        return new OrderCreated(
                UUID.randomUUID(),
                Instant.now(),
                UUID.randomUUID(),
                "cust-001",
                List.of(new OrderLine("sku-1", 2))
        );
    }

    @Test
    void alreadyProcessedEventShortCircuitsWithoutTouchingReservationOrOutbox() {
        processor = new InventoryOrderProcessor(reservationService, processedEventRepository, outboxWriter);
        OrderCreated event = sampleEvent();
        when(processedEventRepository.existsById(event.eventId())).thenReturn(true);

        processor.process(event);

        verifyNoInteractions(reservationService);
        verifyNoInteractions(outboxWriter);
    }

    @Test
    void successOnFirstTryWritesInventoryReservedOutboxEvent() {
        processor = new InventoryOrderProcessor(reservationService, processedEventRepository, outboxWriter);
        OrderCreated event = sampleEvent();
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        doNothing().when(reservationService).reserveLines(event.lines());

        processor.process(event);

        verify(reservationService, times(1)).reserveLines(event.lines());
        verify(outboxWriter).write(
                eq(EventTopics.INVENTORY_RESERVED),
                eq(event.orderId().toString()),
                eq("InventoryReserved"),
                any()
        );
    }

    @Test
    void insufficientStockDoesNotRetryAndWritesInventoryRejectedWithExceptionMessage() {
        processor = new InventoryOrderProcessor(reservationService, processedEventRepository, outboxWriter);
        OrderCreated event = sampleEvent();
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        InsufficientStockException thrown = new InsufficientStockException("Insufficient stock for sku sku-1");
        doThrow(thrown).when(reservationService).reserveLines(event.lines());

        processor.process(event);

        // No retry: insufficient stock is a definitive outcome, not contention.
        verify(reservationService, times(1)).reserveLines(event.lines());

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).write(
                eq(EventTopics.INVENTORY_REJECTED),
                eq(event.orderId().toString()),
                eq("InventoryRejected"),
                payloadCaptor.capture()
        );
        assertThat(payloadCaptor.getValue())
                .extracting("reason")
                .isEqualTo("Insufficient stock for sku sku-1");
    }

    @Test
    void optimisticLockFailureRetriesAndSucceedsOnLaterAttempt() {
        processor = new InventoryOrderProcessor(reservationService, processedEventRepository, outboxWriter);
        OrderCreated event = sampleEvent();
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        doThrow(new ObjectOptimisticLockingFailureException("StockItem", "sku-1"))
                .doNothing()
                .when(reservationService).reserveLines(event.lines());

        processor.process(event);

        verify(reservationService, times(2)).reserveLines(event.lines());
        verify(outboxWriter).write(
                eq(EventTopics.INVENTORY_RESERVED),
                eq(event.orderId().toString()),
                eq("InventoryReserved"),
                any()
        );
    }

    @Test
    void exhaustedRetriesGivesUpAfterThreeAttemptsAndWritesContentionReason() {
        processor = new InventoryOrderProcessor(reservationService, processedEventRepository, outboxWriter);
        OrderCreated event = sampleEvent();
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        doThrow(new ObjectOptimisticLockingFailureException("StockItem", "sku-1"))
                .when(reservationService).reserveLines(event.lines());

        processor.process(event);

        // MAX_OPTIMISTIC_LOCK_RETRIES in InventoryOrderProcessor is 3.
        verify(reservationService, times(3)).reserveLines(event.lines());

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).write(
                eq(EventTopics.INVENTORY_REJECTED),
                eq(event.orderId().toString()),
                eq("InventoryRejected"),
                payloadCaptor.capture()
        );
        assertThat(payloadCaptor.getValue())
                .extracting("reason")
                .isEqualTo("Too much contention reserving stock - please retry");
    }
}
