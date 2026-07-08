package com.orderplatform.notification.messaging;

import com.orderplatform.events.EventTopics;
import com.orderplatform.events.OrderCancelled;
import com.orderplatform.events.OrderConfirmed;
import com.orderplatform.notification.domain.Notification;
import com.orderplatform.notification.domain.NotificationType;
import com.orderplatform.notification.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** Terminal consumer of the order lifecycle: turns a confirmed/cancelled event into a customer-facing notification. */
@Component
public class OrderLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(OrderLifecycleListener.class);

    private final NotificationRepository notificationRepository;

    public OrderLifecycleListener(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @KafkaListener(topics = EventTopics.ORDER_CONFIRMED, groupId = "notification-service")
    public void onOrderConfirmed(OrderConfirmed event) {
        Notification notification = new Notification(
                UUID.randomUUID(),
                event.orderId(),
                NotificationType.ORDER_CONFIRMED,
                "Your order " + event.orderId() + " has been confirmed.",
                Instant.now()
        );
        notificationRepository.save(notification);
        log.info("Notification recorded: {}", notification);
    }

    @KafkaListener(topics = EventTopics.ORDER_CANCELLED, groupId = "notification-service")
    public void onOrderCancelled(OrderCancelled event) {
        Notification notification = new Notification(
                UUID.randomUUID(),
                event.orderId(),
                NotificationType.ORDER_CANCELLED,
                "Your order " + event.orderId() + " was cancelled: " + event.reason(),
                Instant.now()
        );
        notificationRepository.save(notification);
        log.info("Notification recorded: {}", notification);
    }
}
