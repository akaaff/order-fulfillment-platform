package com.orderplatform.notification.repository;

import com.orderplatform.notification.domain.Notification;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Repository
public class NotificationRepository {

    private final Map<UUID, List<Notification>> byOrderId = new ConcurrentHashMap<>();

    public void save(Notification notification) {
        byOrderId.computeIfAbsent(notification.orderId(), id -> new CopyOnWriteArrayList<>())
                .add(notification);
    }

    public List<Notification> findByOrderId(UUID orderId) {
        return byOrderId.getOrDefault(orderId, List.of());
    }

    public List<Notification> findAll() {
        return byOrderId.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }
}
