package com.orderplatform.inventory.messaging;

import com.orderplatform.events.EventTopics;
import com.orderplatform.events.OrderCreated;
import com.orderplatform.inventory.service.InventoryOrderProcessor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Entry point into the Inventory bounded context: every new order attempts a reservation here. */
@Component
public class OrderCreatedListener {

    private final InventoryOrderProcessor processor;

    public OrderCreatedListener(InventoryOrderProcessor processor) {
        this.processor = processor;
    }

    @KafkaListener(topics = EventTopics.ORDER_CREATED, groupId = "inventory-service")
    public void onOrderCreated(OrderCreated event) {
        processor.process(event);
    }
}
