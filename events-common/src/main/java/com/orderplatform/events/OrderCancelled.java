package com.orderplatform.events;

import java.time.Instant;
import java.util.UUID;

public record OrderCancelled(
        UUID eventId,
        Instant occurredAt,
        UUID orderId,
        String reason
) {
}
