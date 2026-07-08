package com.orderplatform.order.domain;

import com.orderplatform.events.OrderLine;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate root for the Ordering bounded context. Identity and line items
 * are fixed at creation; only status transitions once inventory responds,
 * which is why those fields are the only mutable (volatile) state here.
 */
public final class Order {

    private final UUID orderId;
    private final String customerId;
    private final List<OrderLine> lines;
    private final Instant createdAt;
    private volatile OrderStatus status;
    private volatile String cancellationReason;

    public Order(UUID orderId, String customerId, List<OrderLine> lines, Instant createdAt) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.lines = List.copyOf(lines);
        this.createdAt = createdAt;
        this.status = OrderStatus.PENDING;
    }

    public UUID orderId() {
        return orderId;
    }

    public String customerId() {
        return customerId;
    }

    public List<OrderLine> lines() {
        return lines;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public OrderStatus status() {
        return status;
    }

    public String cancellationReason() {
        return cancellationReason;
    }

    public void markConfirmed() {
        this.status = OrderStatus.CONFIRMED;
    }

    public void markCancelled(String reason) {
        this.status = OrderStatus.CANCELLED;
        this.cancellationReason = reason;
    }
}
