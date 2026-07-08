package com.orderplatform.inventory.repository;

import com.orderplatform.inventory.domain.StockItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockItemRepository extends JpaRepository<StockItem, String> {
}
