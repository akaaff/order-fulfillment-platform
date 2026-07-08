package com.orderplatform.events;

import java.time.Instant;
import java.util.UUID;

public record InventoryRejected(
        UUID eventId,
        Instant occurredAt,
        UUID orderId,
        String reason
) {
}
