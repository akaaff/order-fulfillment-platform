package com.orderplatform.order.repository;

import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Collection;
import com.orderplatform.order.domain.Order;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only access to order documents. Writes deliberately don't go through
 * this class - they go through OrderCreationService / InventoryOutcomeProcessor,
 * which need Couchbase's multi-document transactions to keep an order update
 * atomic with its outbox event, not a plain repository save.
 */
@Repository
public class OrderRepository {

    private final Collection collection;

    public OrderRepository(Collection collection) {
        this.collection = collection;
    }

    public Optional<Order> findById(UUID orderId) {
        try {
            return Optional.of(collection.get(key(orderId)).contentAs(Order.class));
        } catch (DocumentNotFoundException e) {
            return Optional.empty();
        }
    }

    public static String key(UUID orderId) {
        return "order::" + orderId;
    }
}
