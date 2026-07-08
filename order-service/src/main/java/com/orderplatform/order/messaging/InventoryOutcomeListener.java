package com.orderplatform.order.messaging;

import com.orderplatform.events.EventTopics;
import com.orderplatform.events.InventoryRejected;
import com.orderplatform.events.InventoryReserved;
import com.orderplatform.order.service.InventoryOutcomeProcessor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InventoryOutcomeListener {

    private final InventoryOutcomeProcessor processor;

    public InventoryOutcomeListener(InventoryOutcomeProcessor processor) {
        this.processor = processor;
    }

    @KafkaListener(topics = EventTopics.INVENTORY_RESERVED, groupId = "order-service")
    public void onInventoryReserved(InventoryReserved event) {
        processor.handleReserved(event);
    }

    @KafkaListener(topics = EventTopics.INVENTORY_REJECTED, groupId = "order-service")
    public void onInventoryRejected(InventoryRejected event) {
        processor.handleRejected(event);
    }
}
