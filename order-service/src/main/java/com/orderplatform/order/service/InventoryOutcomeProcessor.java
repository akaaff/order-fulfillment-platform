package com.orderplatform.order.service;

import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.transactions.TransactionGetResult;
import com.orderplatform.events.EventTopics;
import com.orderplatform.events.InventoryRejected;
import com.orderplatform.events.InventoryReserved;
import com.orderplatform.events.OrderCancelled;
import com.orderplatform.events.OrderConfirmed;
import com.orderplatform.order.domain.Order;
import com.orderplatform.order.outbox.OutboxWriter;
import com.orderplatform.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Applies an inventory reservation outcome to the order it belongs to.
 * The idempotency check, the order status transition, and the resulting
 * outbox event are all staged in one Couchbase transaction, so a redelivered
 * Kafka message is a genuine no-op (not just "processed twice but the second
 * time is harmless") and a crash mid-update can't leave the order updated
 * without the corresponding OrderConfirmed/OrderCancelled event queued.
 */
@Service
public class InventoryOutcomeProcessor {

    private static final Logger log = LoggerFactory.getLogger(InventoryOutcomeProcessor.class);

    private final Cluster cluster;
    private final Collection collection;
    private final OutboxWriter outboxWriter;

    public InventoryOutcomeProcessor(Cluster cluster, Collection collection, OutboxWriter outboxWriter) {
        this.cluster = cluster;
        this.collection = collection;
        this.outboxWriter = outboxWriter;
    }

    public void handleReserved(InventoryReserved event) {
        applyOutcome(
                event.eventId(),
                event.orderId(),
                Order::confirm,
                EventTopics.ORDER_CONFIRMED,
                "OrderConfirmed",
                new OrderConfirmed(UUID.randomUUID(), Instant.now(), event.orderId())
        );
    }

    public void handleRejected(InventoryRejected event) {
        applyOutcome(
                event.eventId(),
                event.orderId(),
                order -> order.cancel(event.reason()),
                EventTopics.ORDER_CANCELLED,
                "OrderCancelled",
                new OrderCancelled(UUID.randomUUID(), Instant.now(), event.orderId(), event.reason())
        );
    }

    private void applyOutcome(
            UUID eventId,
            UUID orderId,
            UnaryOperator<Order> transition,
            String topic,
            String eventType,
            Object outboundEvent
    ) {
        String processedKey = "processed::" + eventId;
        String orderKey = OrderRepository.key(orderId);

        cluster.transactions().run(ctx -> {
            try {
                ctx.get(collection, processedKey);
                return; // already handled - redelivered event, no-op
            } catch (DocumentNotFoundException notYetProcessed) {
                // proceed
            }

            TransactionGetResult orderDoc;
            try {
                orderDoc = ctx.get(collection, orderKey);
            } catch (DocumentNotFoundException unknownOrder) {
                log.warn("Received inventory outcome for unknown orderId={}", orderId);
                return;
            }

            ctx.replace(orderDoc, transition.apply(orderDoc.contentAs(Order.class)));
            ctx.insert(collection, processedKey, Map.of("processedAt", Instant.now().toString()));
            outboxWriter.write(ctx, collection, topic, orderId.toString(), eventType, outboundEvent);
        });
    }
}
