package com.orderplatform.inventory.repository;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory placeholder for Day 1. Replaced by a Postgres-backed
 * implementation with optimistic locking once real persistence lands -
 * the synchronized decrement below is only safe within a single instance.
 */
@Repository
public class StockRepository {

    private final Map<String, Integer> stockBySku = new ConcurrentHashMap<>();

    @PostConstruct
    void seedDemoStock() {
        stockBySku.put("SKU-WIDGET", 50);
        stockBySku.put("SKU-GADGET", 20);
        stockBySku.put("SKU-GIZMO", 5);
    }

    public int available(String sku) {
        return stockBySku.getOrDefault(sku, 0);
    }

    /**
     * Attempts to decrement stock for the given sku. Returns true if there was
     * enough stock and the decrement was applied, false otherwise.
     */
    public synchronized boolean tryReserve(String sku, int quantity) {
        int current = stockBySku.getOrDefault(sku, 0);
        if (current < quantity) {
            return false;
        }
        stockBySku.put(sku, current - quantity);
        return true;
    }

    public synchronized void release(String sku, int quantity) {
        stockBySku.merge(sku, quantity, Integer::sum);
    }
}
