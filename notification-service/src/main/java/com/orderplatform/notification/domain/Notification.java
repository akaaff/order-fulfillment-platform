package com.orderplatform.notification.domain;

import java.time.Instant;
import java.util.UUID;

/** A recorded notification for an order lifecycle event - stands in for an actual email/SMS provider. */
public record Notification(
        UUID notificationId,
        UUID orderId,
        NotificationType type,
        String message,
        Instant sentAt
) {
}
