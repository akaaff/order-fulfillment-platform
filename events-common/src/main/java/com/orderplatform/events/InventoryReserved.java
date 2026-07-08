package com.orderplatform.events;

import java.time.Instant;
import java.util.UUID;

public record InventoryReserved(
        UUID eventId,
        Instant occurredAt,
        UUID orderId
) {
}
