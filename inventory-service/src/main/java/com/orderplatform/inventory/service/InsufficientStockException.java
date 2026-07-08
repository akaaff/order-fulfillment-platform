package com.orderplatform.inventory.service;

/** Signals that a line on an order couldn't be reserved; caught by InventoryOrderProcessor, never allowed to escape it. */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String message) {
        super(message);
    }
}
