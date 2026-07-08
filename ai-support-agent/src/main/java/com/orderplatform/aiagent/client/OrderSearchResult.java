package com.orderplatform.aiagent.client;

/** Subset of order-service's OrderSearchDocument this agent actually needs. */
public record OrderSearchResult(String orderId, String status, String cancellationReason) {
}
