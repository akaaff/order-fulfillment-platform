package com.orderplatform.order.api.dto;

import com.orderplatform.events.OrderLine;
import com.orderplatform.order.domain.Order;
import com.orderplatform.order.domain.OrderStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID orderId,
        String customerId,
        List<OrderLine> lines,
        OrderStatus status,
        String cancellationReason,
        Instant createdAt
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.orderId(),
                order.customerId(),
                order.lines(),
                order.status(),
                order.cancellationReason(),
                order.createdAt()
        );
    }
}
