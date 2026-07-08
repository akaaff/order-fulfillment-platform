package com.orderplatform.events;

import java.time.Instant;
import java.util.UUID;

/** Published by order-service after inventory confirms the reservation succeeded. */
public record OrderConfirmed(
        UUID eventId,
        Instant occurredAt,
        UUID orderId
) {
}
