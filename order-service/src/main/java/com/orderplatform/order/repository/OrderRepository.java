package com.orderplatform.order.repository;

import com.orderplatform.order.domain.Order;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory placeholder repository for Day 1. Replaced by a Couchbase-backed
 * implementation once real persistence lands.
 */
@Repository
public class OrderRepository {

    private final Map<UUID, Order> orders = new ConcurrentHashMap<>();

    public Order save(Order order) {
        orders.put(order.orderId(), order);
        return order;
    }

    public Optional<Order> findById(UUID orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }
}
