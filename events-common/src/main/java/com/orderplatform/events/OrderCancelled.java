package com.orderplatform.events;

import java.time.Instant;
import java.util.UUID;

/** Published by order-service when inventory rejects the reservation; {@code reason} is customer-facing. */
public record OrderCancelled(
        UUID eventId,
        Instant occurredAt,
        UUID orderId,
        String reason
) {
}
