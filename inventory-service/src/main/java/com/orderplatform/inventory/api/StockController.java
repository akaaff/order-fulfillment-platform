package com.orderplatform.inventory.api;

import com.orderplatform.inventory.repository.StockItemRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Read-only debug/demo endpoint for inspecting current stock levels.
 */
@RestController
public class StockController {

    private final StockItemRepository stockItemRepository;

    public StockController(StockItemRepository stockItemRepository) {
        this.stockItemRepository = stockItemRepository;
    }

    @GetMapping("/inventory/{sku}")
    public Map<String, Object> getStock(@PathVariable String sku) {
        int available = stockItemRepository.findById(sku)
                .map(item -> item.getQuantity())
                .orElse(0);
        return Map.of("sku", sku, "available", available);
    }
}
