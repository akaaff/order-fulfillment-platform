package com.orderplatform.order.messaging;

import com.orderplatform.events.EventTopics;
import com.orderplatform.events.InventoryRejected;
import com.orderplatform.events.InventoryReserved;
import com.orderplatform.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InventoryOutcomeListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryOutcomeListener.class);

    private final OrderRepository orderRepository;
    private final OrderEventPublisher publisher;

    public InventoryOutcomeListener(OrderRepository orderRepository, OrderEventPublisher publisher) {
        this.orderRepository = orderRepository;
        this.publisher = publisher;
    }

    @KafkaListener(topics = EventTopics.INVENTORY_RESERVED, groupId = "order-service")
    public void onInventoryReserved(InventoryReserved event) {
        orderRepository.findById(event.orderId()).ifPresentOrElse(order -> {
            order.markConfirmed();
            publisher.publishOrderConfirmed(order.orderId());
        }, () -> log.warn("Received InventoryReserved for unknown orderId={}", event.orderId()));
    }

    @KafkaListener(topics = EventTopics.INVENTORY_REJECTED, groupId = "order-service")
    public void onInventoryRejected(InventoryRejected event) {
        orderRepository.findById(event.orderId()).ifPresentOrElse(order -> {
            order.markCancelled(event.reason());
            publisher.publishOrderCancelled(order.orderId(), event.reason());
        }, () -> log.warn("Received InventoryRejected for unknown orderId={}", event.orderId()));
    }
}
