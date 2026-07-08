package com.orderplatform.order.domain;

import com.orderplatform.events.OrderLine;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate root for the Ordering bounded context, stored as a Couchbase
 * document keyed "order::&lt;orderId&gt;". Immutable by design - a status
 * transition produces a new instance rather than mutating in place, which is
 * what lets every update go through Couchbase's CAS-based replace (or, for
 * the reservation outcome, a full multi-document transaction) instead of a
 * race-prone read-modify-write.
 */
public record Order(
        UUID orderId,
        String customerId,
        List<OrderLine> lines,
        OrderStatus status,
        String cancellationReason,
        Instant createdAt
) {
    public static Order create(UUID orderId, String customerId, List<OrderLine> lines, Instant createdAt) {
        return new Order(orderId, customerId, List.copyOf(lines), OrderStatus.PENDING, null, createdAt);
    }

    public Order confirm() {
        return new Order(orderId, customerId, lines, OrderStatus.CONFIRMED, null, createdAt);
    }

    public Order cancel(String reason) {
        return new Order(orderId, customerId, lines, OrderStatus.CANCELLED, reason, createdAt);
    }
}
