package com.orderplatform.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderCreated(
        UUID eventId,
        Instant occurredAt,
        UUID orderId,
        String customerId,
        List<OrderLine> lines
) {
}
