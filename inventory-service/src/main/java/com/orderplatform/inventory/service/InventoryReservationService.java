package com.orderplatform.inventory.service;

import com.orderplatform.events.OrderLine;
import com.orderplatform.inventory.domain.StockItem;
import com.orderplatform.inventory.repository.StockItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class InventoryReservationService {

    private final StockItemRepository stockItemRepository;

    public InventoryReservationService(StockItemRepository stockItemRepository) {
        this.stockItemRepository = stockItemRepository;
    }

    /**
     * Reserves every line or none of them. Runs in its own transaction
     * (REQUIRES_NEW) so that when a line fails, the database itself rolls
     * back whatever earlier lines in this call already decremented - no
     * manual compensating "release" logic needed, unlike the in-memory
     * version this replaced. Concurrent reservations against the same sku
     * surface as ObjectOptimisticLockingFailureException (via StockItem's
     * @Version column) for the caller to retry.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reserveLines(List<OrderLine> lines) {
        for (OrderLine line : lines) {
            StockItem item = stockItemRepository.findById(line.sku())
                    .orElseThrow(() -> new InsufficientStockException("Unknown sku " + line.sku()));

            if (item.getQuantity() < line.quantity()) {
                throw new InsufficientStockException("Insufficient stock for sku " + line.sku());
            }

            item.setQuantity(item.getQuantity() - line.quantity());
        }
    }
}
