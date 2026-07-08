package com.orderplatform.events;

/**
 * Canonical Kafka topic names shared by every producer and consumer, so a
 * topic is never typo'd or duplicated with a different literal in each service.
 */
public final class EventTopics {

    public static final String ORDER_CREATED = "order.created";
    public static final String INVENTORY_RESERVED = "inventory.reserved";
    public static final String INVENTORY_REJECTED = "inventory.rejected";
    public static final String ORDER_CONFIRMED = "order.confirmed";
    public static final String ORDER_CANCELLED = "order.cancelled";

    private EventTopics() {
    }
}
