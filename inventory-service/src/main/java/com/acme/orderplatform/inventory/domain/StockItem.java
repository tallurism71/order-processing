package com.acme.orderplatform.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "stock_item")
public class StockItem {

    @Id
    @Column(length = 64)
    private String sku;

    @Column(nullable = false)
    private int available;

    @Column(nullable = false)
    private int reserved;

    @Version
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StockItem() {
    }

    public StockItem(String sku, int available) {
        this.sku = sku;
        this.available = available;
        this.updatedAt = Instant.now();
    }

    public boolean canReserve(int quantity) {
        return available >= quantity;
    }

    public void reserve(int quantity) {
        if (!canReserve(quantity)) {
            throw new IllegalStateException("Insufficient stock for " + sku);
        }
        available -= quantity;
        reserved += quantity;
        updatedAt = Instant.now();
    }

    public void release(int quantity) {
        reserved -= quantity;
        available += quantity;
        updatedAt = Instant.now();
    }

    /** Reserved units leave the warehouse when the order is confirmed. */
    public void commit(int quantity) {
        reserved -= quantity;
        updatedAt = Instant.now();
    }

    public void restock(int newAvailable) {
        if (newAvailable < 0) {
            throw new IllegalArgumentException("available must be >= 0");
        }
        available = newAvailable;
        updatedAt = Instant.now();
    }

    public String getSku() {
        return sku;
    }

    public int getAvailable() {
        return available;
    }

    public int getReserved() {
        return reserved;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
