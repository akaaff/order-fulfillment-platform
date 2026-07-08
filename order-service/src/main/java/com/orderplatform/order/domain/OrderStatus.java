package com.orderplatform.order.domain;

/** Lifecycle state of an order. PENDING is the only state that awaits an inventory outcome. */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    CANCELLED
}
