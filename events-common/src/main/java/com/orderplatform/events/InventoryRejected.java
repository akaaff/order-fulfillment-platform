package com.orderplatform.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by inventory-service when any line on an order can't be reserved.
 * By the time this is published, any partial reservation for the order has
 * already been rolled back - see InventoryReservationService.
 */
public record InventoryRejected(
        UUID eventId,
        Instant occurredAt,
        UUID orderId,
        String reason
) {
}
