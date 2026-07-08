package com.orderplatform.inventory.messaging;

import com.orderplatform.events.EventTopics;
import com.orderplatform.events.InventoryRejected;
import com.orderplatform.events.InventoryReserved;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class InventoryEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public InventoryEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishReserved(UUID orderId) {
        InventoryReserved event = new InventoryReserved(UUID.randomUUID(), Instant.now(), orderId);
        kafkaTemplate.send(EventTopics.INVENTORY_RESERVED, orderId.toString(), event);
    }

    public void publishRejected(UUID orderId, String reason) {
        InventoryRejected event = new InventoryRejected(UUID.randomUUID(), Instant.now(), orderId, reason);
        kafkaTemplate.send(EventTopics.INVENTORY_REJECTED, orderId.toString(), event);
    }
}
