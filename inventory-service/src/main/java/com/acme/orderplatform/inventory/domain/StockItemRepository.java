package com.acme.orderplatform.inventory.domain;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface StockItemRepository extends JpaRepository<StockItem, String> {

    /**
     * Row locks (SELECT ... FOR UPDATE) taken in SKU order. A fixed lock order means two orders for
     * overlapping SKUs can't deadlock; the lock timeout keeps a hot SKU from stalling consumers.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select s from StockItem s where s.sku in :skus order by s.sku")
    List<StockItem> lockAll(@Param("skus") Collection<String> skus);
}
