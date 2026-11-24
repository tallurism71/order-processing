package com.acme.orderplatform.inventory.service;

import com.acme.orderplatform.common.events.EventEnvelope;
import com.acme.orderplatform.common.events.EventTypes;
import com.acme.orderplatform.common.events.Events;
import com.acme.orderplatform.common.outbox.OutboxWriter;
import com.acme.orderplatform.inventory.domain.Reservation;
import com.acme.orderplatform.inventory.domain.ReservationRepository;
import com.acme.orderplatform.inventory.domain.StockItem;
import com.acme.orderplatform.inventory.domain.StockItemRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryServiceTest {

    private final StockItemRepository stock = mock(StockItemRepository.class);
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final InventoryService service = new InventoryService(stock, reservations, outbox, mock(CacheManager.class),
            new SimpleMeterRegistry());

    private final UUID orderId = UUID.randomUUID();
    private final EventEnvelope cause = new EventEnvelope(UUID.randomUUID(), EventTypes.ORDER_CREATED, 1, "Order",
            orderId.toString(), "corr", null, "order-service", Instant.now(), null);

    @BeforeEach
    void startSync() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearSync() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private Events.OrderCreated order(Events.OrderCreated.Line... lines) {
        return new Events.OrderCreated(orderId, "c-1", new BigDecimal("30.00"), "USD", List.of(lines));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reservesAllLinesAndLocksSkusInSortedOrder() {
        StockItem a = new StockItem("SKU-A", 10);
        StockItem b = new StockItem("SKU-B", 10);
        when(stock.lockAll(anyCollection())).thenReturn(List.of(a, b));

        service.reserve(order(new Events.OrderCreated.Line("SKU-B", 2, BigDecimal.TEN),
                new Events.OrderCreated.Line("SKU-A", 1, BigDecimal.TEN),
                new Events.OrderCreated.Line("SKU-B", 1, BigDecimal.TEN)), cause);

        ArgumentCaptor<Collection<String>> skus = ArgumentCaptor.forClass(Collection.class);
        verify(stock).lockAll(skus.capture());
        assertThat(skus.getValue()).containsExactly("SKU-A", "SKU-B");
        assertThat(a.getAvailable()).isEqualTo(9);
        assertThat(b.getAvailable()).isEqualTo(7);
        assertThat(b.getReserved()).isEqualTo(3);
        verify(outbox).write(any(), eq(EventTypes.INVENTORY_RESERVED), any(), eq(orderId.toString()), any(), eq(cause.eventId()));
    }

    @Test
    void shortageRejectsWholeOrderAndTouchesNoStock() {
        StockItem a = new StockItem("SKU-A", 10);
        StockItem b = new StockItem("SKU-B", 1);
        when(stock.lockAll(anyCollection())).thenReturn(List.of(a, b));

        service.reserve(order(new Events.OrderCreated.Line("SKU-A", 1, BigDecimal.TEN),
                new Events.OrderCreated.Line("SKU-B", 5, BigDecimal.TEN)), cause);

        assertThat(a.getAvailable()).isEqualTo(10);
        assertThat(b.getAvailable()).isEqualTo(1);
        verify(reservations, never()).save(any());
        verify(outbox).write(any(), eq(EventTypes.INVENTORY_REJECTED), any(), any(), any(), any());
    }

    @Test
    void releaseReturnsStockAndIsIdempotent() {
        StockItem a = new StockItem("SKU-A", 10);
        a.reserve(4);
        Reservation r = new Reservation(orderId, "SKU-A", 4);
        when(reservations.findByOrderIdAndStatus(orderId, Reservation.Status.ACTIVE)).thenReturn(List.of(r)).thenReturn(List.of());
        when(stock.lockAll(anyCollection())).thenReturn(List.of(a));

        service.release(orderId);
        service.release(orderId);

        assertThat(a.getAvailable()).isEqualTo(10);
        assertThat(a.getReserved()).isZero();
        assertThat(r.getStatus()).isEqualTo(Reservation.Status.RELEASED);
    }
}
