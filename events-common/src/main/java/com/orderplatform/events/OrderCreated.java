package com.orderplatform.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by order-service once an order is accepted, before inventory has
 * reserved anything. {@code eventId} is distinct from {@code orderId} so
 * consumers can dedupe retried deliveries once idempotency is added.
 */
public record OrderCreated(
        UUID eventId,
        Instant occurredAt,
        UUID orderId,
        String customerId,
        List<OrderLine> lines
) {
}
