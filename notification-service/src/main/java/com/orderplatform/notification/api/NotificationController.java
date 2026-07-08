package com.orderplatform.notification.api;

import com.orderplatform.notification.domain.Notification;
import com.orderplatform.notification.repository.NotificationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class NotificationController {

    private final NotificationRepository notificationRepository;

    public NotificationController(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @GetMapping("/notifications")
    public List<Notification> getAll() {
        return notificationRepository.findAll();
    }

    @GetMapping("/notifications/order/{orderId}")
    public List<Notification> getByOrderId(@PathVariable UUID orderId) {
        return notificationRepository.findByOrderId(orderId);
    }
}
