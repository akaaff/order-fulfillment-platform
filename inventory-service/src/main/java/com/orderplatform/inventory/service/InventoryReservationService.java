package com.orderplatform.inventory.service;

import com.orderplatform.events.OrderLine;
import com.orderplatform.inventory.repository.StockRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class InventoryReservationService {

    private final StockRepository stockRepository;

    public InventoryReservationService(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    /**
     * Reserves every line or none of them - a rejection on any single line
     * releases whatever was already reserved for this order.
     */
    public ReservationResult reserve(List<OrderLine> lines) {
        List<OrderLine> reservedSoFar = new ArrayList<>();

        for (OrderLine line : lines) {
            boolean ok = stockRepository.tryReserve(line.sku(), line.quantity());
            if (!ok) {
                reservedSoFar.forEach(done -> stockRepository.release(done.sku(), done.quantity()));
                return ReservationResult.rejected("Insufficient stock for sku " + line.sku());
            }
            reservedSoFar.add(line);
        }

        return ReservationResult.success();
    }
}
