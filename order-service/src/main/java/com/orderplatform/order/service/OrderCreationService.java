package com.orderplatform.order.service;

import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.orderplatform.events.EventTopics;
import com.orderplatform.events.OrderCreated;
import com.orderplatform.events.OrderLine;
import com.orderplatform.order.domain.Order;
import com.orderplatform.order.outbox.OutboxWriter;
import com.orderplatform.order.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Writes the new Order document and its OrderCreated outbox event in a
 * single Couchbase transaction, so a crash between the two is impossible -
 * either both are visible or neither is. This is the dual-write problem
 * (domain write + event write landing in different stores) solved with
 * Couchbase's native multi-document ACID transactions rather than a second,
 * relational outbox table the way inventory-service does it.
 */
@Service
public class OrderCreationService {

    private final Cluster cluster;
    private final Collection collection;
    private final OutboxWriter outboxWriter;

    public OrderCreationService(Cluster cluster, Collection collection, OutboxWriter outboxWriter) {
        this.cluster = cluster;
        this.collection = collection;
        this.outboxWriter = outboxWriter;
    }

    public Order createOrder(String customerId, List<OrderLine> lines) {
        UUID orderId = UUID.randomUUID();
        Instant now = Instant.now();
        Order order = Order.create(orderId, customerId, lines, now);
        OrderCreated event = new OrderCreated(UUID.randomUUID(), now, orderId, customerId, lines);

        cluster.transactions().run(ctx -> {
            ctx.insert(collection, OrderRepository.key(orderId), order);
            outboxWriter.write(ctx, collection, EventTopics.ORDER_CREATED, orderId.toString(), "OrderCreated", event);
        });

        return order;
    }
}
