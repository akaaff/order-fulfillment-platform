package com.orderplatform.inventory.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Version;

/**
 * One row per sku. {@code version} is Hibernate's optimistic-locking column -
 * two concurrent reservation attempts against the same sku will have one of
 * them fail with ObjectOptimisticLockingFailureException at commit, which
 * InventoryReservationService retries rather than silently overwriting.
 */
@Entity
public class StockItem {

    @Id
    private String sku;

    private int quantity;

    @Version
    private long version;

    protected StockItem() {
        // JPA
    }

    public StockItem(String sku, int quantity) {
        this.sku = sku;
        this.quantity = quantity;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }
}
