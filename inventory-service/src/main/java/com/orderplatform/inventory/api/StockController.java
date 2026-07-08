package com.orderplatform.inventory.api;

import com.orderplatform.inventory.repository.StockRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Read-only debug/demo endpoint for inspecting current stock levels.
 */
@RestController
public class StockController {

    private final StockRepository stockRepository;

    public StockController(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    @GetMapping("/inventory/{sku}")
    public Map<String, Object> getStock(@PathVariable String sku) {
        return Map.of("sku", sku, "available", stockRepository.available(sku));
    }
}
