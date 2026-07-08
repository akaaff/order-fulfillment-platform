package com.orderplatform.inventory.service;

import com.orderplatform.events.EventTopics;
import com.orderplatform.events.InventoryRejected;
import com.orderplatform.events.InventoryReserved;
import com.orderplatform.events.OrderCreated;
import com.orderplatform.events.OrderLine;
import com.orderplatform.inventory.outbox.OutboxWriter;
import com.orderplatform.inventory.outbox.ProcessedEvent;
import com.orderplatform.inventory.outbox.ProcessedEventRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates one order.created event end to end: idempotency check,
 * reservation attempt (with retry on optimistic-lock contention), and
 * recording the outcome. The idempotency marker and the outbox event are
 * written in the same transaction as each other (though not the same
 * transaction as the reservation itself - see reserveLines) so a crash
 * between "stock reserved" and "outcome recorded" can't happen silently.
 */
@Service
public class InventoryOrderProcessor {

    private static final int MAX_OPTIMISTIC_LOCK_RETRIES = 3;

    private final InventoryReservationService reservationService;
    private final ProcessedEventRepository processedEventRepository;
    private final OutboxWriter outboxWriter;

    public InventoryOrderProcessor(
            InventoryReservationService reservationService,
            ProcessedEventRepository processedEventRepository,
            OutboxWriter outboxWriter
    ) {
        this.reservationService = reservationService;
        this.processedEventRepository = processedEventRepository;
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public void process(OrderCreated event) {
        if (processedEventRepository.existsById(event.eventId())) {
            return;
        }

        ReservationResult result = attemptReservation(event.lines());
        processedEventRepository.save(new ProcessedEvent(event.eventId(), Instant.now()));

        if (result.reserved()) {
            outboxWriter.write(
                    EventTopics.INVENTORY_RESERVED,
                    event.orderId().toString(),
                    "InventoryReserved",
                    new InventoryReserved(UUID.randomUUID(), Instant.now(), event.orderId())
            );
        } else {
            outboxWriter.write(
                    EventTopics.INVENTORY_REJECTED,
                    event.orderId().toString(),
                    "InventoryRejected",
                    new InventoryRejected(UUID.randomUUID(), Instant.now(), event.orderId(), result.rejectionReason())
            );
        }
    }

    private ReservationResult attemptReservation(List<OrderLine> lines) {
        for (int attempt = 1; attempt <= MAX_OPTIMISTIC_LOCK_RETRIES; attempt++) {
            try {
                reservationService.reserveLines(lines);
                return ReservationResult.success();
            } catch (InsufficientStockException e) {
                return ReservationResult.rejected(e.getMessage());
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt == MAX_OPTIMISTIC_LOCK_RETRIES) {
                    return ReservationResult.rejected("Too much contention reserving stock - please retry");
                }
                // another order reserved the same sku concurrently; loop and retry with fresh data
            }
        }
        throw new IllegalStateException("unreachable");
    }
}
