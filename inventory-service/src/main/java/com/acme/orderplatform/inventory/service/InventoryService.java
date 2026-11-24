package com.acme.orderplatform.inventory.service;

import com.acme.orderplatform.common.events.EventEnvelope;
import com.acme.orderplatform.common.events.EventTypes;
import com.acme.orderplatform.common.events.Events;
import com.acme.orderplatform.common.events.Topics;
import com.acme.orderplatform.common.outbox.OutboxWriter;
import com.acme.orderplatform.common.web.ResourceNotFoundException;
import com.acme.orderplatform.inventory.domain.Reservation;
import com.acme.orderplatform.inventory.domain.ReservationRepository;
import com.acme.orderplatform.inventory.domain.StockItem;
import com.acme.orderplatform.inventory.domain.StockItemRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class InventoryService {

    public static final String STOCK_CACHE = "stock";
    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    public record StockView(String sku, int available, int reserved) {
    }

    private final StockItemRepository stock;
    private final ReservationRepository reservations;
    private final OutboxWriter outbox;
    private final CacheManager cacheManager;
    private final MeterRegistry metrics;

    public InventoryService(StockItemRepository stock, ReservationRepository reservations, OutboxWriter outbox,
                            CacheManager cacheManager, MeterRegistry metrics) {
        this.stock = stock;
        this.reservations = reservations;
        this.outbox = outbox;
        this.cacheManager = cacheManager;
        this.metrics = metrics;
    }

    /** All-or-nothing reservation across every line of the order. Runs inside the caller's transaction. */
    public void reserve(Events.OrderCreated order, EventEnvelope cause) {
        // Merge duplicate SKUs and sort, so locks are always acquired in the same order.
        Map<String, Integer> wanted = order.lines().stream()
                .collect(Collectors.toMap(Events.OrderCreated.Line::sku, Events.OrderCreated.Line::quantity, Integer::sum, TreeMap::new));
        Map<String, StockItem> items = stock.lockAll(wanted.keySet()).stream()
                .collect(Collectors.toMap(StockItem::getSku, Function.identity()));

        String shortage = wanted.entrySet().stream()
                .filter(e -> !items.containsKey(e.getKey()) || !items.get(e.getKey()).canReserve(e.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.joining(","));

        String orderId = order.orderId().toString();
        if (!shortage.isEmpty()) {
            metrics.counter("inventory.reservations", "result", "rejected").increment();
            outbox.write(Topics.INVENTORY_EVENTS, EventTypes.INVENTORY_REJECTED, "Order", orderId,
                    new Events.InventoryRejected(order.orderId(), "Insufficient stock: " + shortage), cause.eventId());
            log.info("Rejected order {}: insufficient stock for {}", orderId, shortage);
            return;
        }

        wanted.forEach((sku, qty) -> {
            items.get(sku).reserve(qty);
            reservations.save(new Reservation(order.orderId(), sku, qty));
        });
        evictAfterCommit(wanted.keySet().stream().toList());
        metrics.counter("inventory.reservations", "result", "reserved").increment();
        outbox.write(Topics.INVENTORY_EVENTS, EventTypes.INVENTORY_RESERVED, "Order", orderId,
                new Events.InventoryReserved(order.orderId(), order.customerId(), order.totalAmount(), order.currency()),
                cause.eventId());
        log.info("Reserved stock for order {}: {}", orderId, wanted);
    }

    /** Compensation: give reserved stock back when the order is cancelled or rejected. Safe to call twice. */
    public void release(UUID orderId) {
        List<Reservation> active = reservations.findByOrderIdAndStatus(orderId, Reservation.Status.ACTIVE);
        if (active.isEmpty()) {
            return;
        }
        Map<String, StockItem> items = stock.lockAll(active.stream().map(Reservation::getSku).sorted().toList()).stream()
                .collect(Collectors.toMap(StockItem::getSku, Function.identity()));
        active.forEach(r -> {
            items.get(r.getSku()).release(r.getQuantity());
            r.markReleased();
        });
        evictAfterCommit(items.keySet().stream().toList());
        metrics.counter("inventory.reservations", "result", "released").increment();
        log.info("Released {} reservation(s) for order {}", active.size(), orderId);
    }

    public void commit(UUID orderId) {
        List<Reservation> active = reservations.findByOrderIdAndStatus(orderId, Reservation.Status.ACTIVE);
        if (active.isEmpty()) {
            return;
        }
        Map<String, StockItem> items = stock.lockAll(active.stream().map(Reservation::getSku).sorted().toList()).stream()
                .collect(Collectors.toMap(StockItem::getSku, Function.identity()));
        active.forEach(r -> {
            items.get(r.getSku()).commit(r.getQuantity());
            r.markCommitted();
        });
        evictAfterCommit(items.keySet().stream().toList());
    }

    /** Display-only read; may be up to the cache TTL stale. Reservations always lock the DB row. */
    @Cacheable(cacheNames = STOCK_CACHE, key = "#sku")
    @Transactional(readOnly = true)
    public StockView getStock(String sku) {
        return stock.findById(sku).map(InventoryService::view)
                .orElseThrow(() -> new ResourceNotFoundException("SKU", sku));
    }

    @CacheEvict(cacheNames = STOCK_CACHE, key = "#sku")
    @Transactional
    public StockView restock(String sku, int available) {
        StockItem item = stock.findById(sku).orElseGet(() -> stock.save(new StockItem(sku, 0)));
        item.restock(available);
        return view(item);
    }

    private static StockView view(StockItem s) {
        return new StockView(s.getSku(), s.getAvailable(), s.getReserved());
    }

    private void evictAfterCommit(List<String> skus) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        Cache cache = cacheManager.getCache(STOCK_CACHE);
                        if (cache != null) {
                            skus.forEach(cache::evictIfPresent);
                        }
                    }
                });
    }
}
