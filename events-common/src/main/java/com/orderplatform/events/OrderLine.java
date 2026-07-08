package com.orderplatform.events;

/** A single requested sku/quantity pair on an order. */
public record OrderLine(String sku, int quantity) {
}
