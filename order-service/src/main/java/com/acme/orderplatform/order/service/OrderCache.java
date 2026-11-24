package com.acme.orderplatform.order.service;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Evicts the cached order only AFTER the database commit. Evicting before commit lets a concurrent
 * read re-cache the old row and serve stale data until the TTL expires.
 */
@Component
public class OrderCache {

    public static final String ORDERS = "orders";

    private final CacheManager cacheManager;

    public OrderCache(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public void evictAfterCommit(UUID orderId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(orderId);
                }
            });
        } else {
            evict(orderId);
        }
    }

    private void evict(UUID orderId) {
        Cache cache = cacheManager.getCache(ORDERS);
        if (cache != null) {
            cache.evictIfPresent(orderId);
        }
    }
}
