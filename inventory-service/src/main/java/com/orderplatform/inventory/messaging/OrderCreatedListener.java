package com.orderplatform.inventory.messaging;

import com.orderplatform.events.EventTopics;
import com.orderplatform.events.OrderCreated;
import com.orderplatform.inventory.service.InventoryReservationService;
import com.orderplatform.inventory.service.ReservationResult;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Entry point into the Inventory bounded context: every new order attempts a reservation here. */
@Component
public class OrderCreatedListener {

    private final InventoryReservationService reservationService;
    private final InventoryEventPublisher publisher;

    public OrderCreatedListener(InventoryReservationService reservationService, InventoryEventPublisher publisher) {
        this.reservationService = reservationService;
        this.publisher = publisher;
    }

    @KafkaListener(topics = EventTopics.ORDER_CREATED, groupId = "inventory-service")
    public void onOrderCreated(OrderCreated event) {
        ReservationResult result = reservationService.reserve(event.lines());
        if (result.reserved()) {
            publisher.publishReserved(event.orderId());
        } else {
            publisher.publishRejected(event.orderId(), result.rejectionReason());
        }
    }
}
