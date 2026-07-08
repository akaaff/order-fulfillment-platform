package com.orderplatform.notification.domain;

import java.time.Instant;
import java.util.UUID;

public record Notification(
        UUID notificationId,
        UUID orderId,
        NotificationType type,
        String message,
        Instant sentAt
) {
}
