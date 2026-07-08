package com.orderplatform.order.messaging;

import com.orderplatform.events.EventTopics;
import com.orderplatform.events.OrderCancelled;
import com.orderplatform.events.OrderConfirmed;
import com.orderplatform.events.OrderCreated;
import com.orderplatform.order.domain.Order;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** Publishes every order lifecycle event, keyed by orderId so all events for one order land on the same partition and stay in order. */
@Component
public class OrderEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public OrderEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishOrderCreated(Order order) {
        OrderCreated event = new OrderCreated(
                UUID.randomUUID(),
                Instant.now(),
                order.orderId(),
                order.customerId(),
                order.lines()
        );
        kafkaTemplate.send(EventTopics.ORDER_CREATED, order.orderId().toString(), event);
    }

    public void publishOrderConfirmed(UUID orderId) {
        OrderConfirmed event = new OrderConfirmed(UUID.randomUUID(), Instant.now(), orderId);
        kafkaTemplate.send(EventTopics.ORDER_CONFIRMED, orderId.toString(), event);
    }

    public void publishOrderCancelled(UUID orderId, String reason) {
        OrderCancelled event = new OrderCancelled(UUID.randomUUID(), Instant.now(), orderId, reason);
        kafkaTemplate.send(EventTopics.ORDER_CANCELLED, orderId.toString(), event);
    }
}
