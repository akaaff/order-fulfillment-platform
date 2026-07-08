package com.orderplatform.aiagent.client;

/**
 * Subset of order-service's OrderResponse this agent actually needs.
 * Jackson ignores the extra fields (e.g. "lines") Spring Boot's
 * auto-configured ObjectMapper doesn't fail on unknown properties.
 */
public record OrderView(String orderId, String customerId, String status, String cancellationReason) {
}
