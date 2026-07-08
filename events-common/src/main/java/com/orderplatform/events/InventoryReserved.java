package com.orderplatform.events;

import java.time.Instant;
import java.util.UUID;

/** Published by inventory-service once every line on an order has been reserved. */
public record InventoryReserved(
        UUID eventId,
        Instant occurredAt,
        UUID orderId
) {
}
