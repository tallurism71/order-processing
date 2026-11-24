package com.acme.orderplatform.order.service;

import com.acme.orderplatform.common.events.EventCodec;
import com.acme.orderplatform.common.events.EventEnvelope;
import com.acme.orderplatform.common.events.EventTypes;
import com.acme.orderplatform.common.events.Events;
import com.acme.orderplatform.common.events.Topics;
import com.acme.orderplatform.common.inbox.ProcessedEventStore;
import com.acme.orderplatform.common.outbox.OutboxWriter;
import com.acme.orderplatform.order.domain.CustomerOrder;
import com.acme.orderplatform.order.domain.OrderLine;
import com.acme.orderplatform.order.domain.OrderRepository;
import com.acme.orderplatform.order.domain.OrderStatus;
import com.acme.orderplatform.order.messaging.OrderSagaHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderSagaHandlerTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final OrderRepository repository = mock(OrderRepository.class);
    private final ProcessedEventStore processed = mock(ProcessedEventStore.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final OrderSagaHandler handler = new OrderSagaHandler(repository, processed, outbox, new EventCodec(mapper),
            mock(OrderCache.class), metrics);

    private final CustomerOrder order = CustomerOrder.place("c-1", "USD",
            List.of(new OrderLine("SKU-1", 1, BigDecimal.TEN)), "key-1", "h");

    private EventEnvelope event(String type, Object payload) {
        return new EventEnvelope(UUID.randomUUID(), type, 1, "Order", order.getId().toString(), "corr",
                null, "test", Instant.now(), mapper.valueToTree(payload));
    }

    @Test
    void paymentFailureRejectsOrderAndTriggersCompensation() {
        order.transitionTo(OrderStatus.INVENTORY_RESERVED, "reserved");
        when(processed.markProcessed(any(), any())).thenReturn(true);
        when(repository.findById(order.getId())).thenReturn(Optional.of(order));
        var event = event(EventTypes.PAYMENT_FAILED, new Events.PaymentFailed(order.getId(), "Card declined"));

        handler.handle(event);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getFailureReason()).isEqualTo("Card declined");
        verify(outbox).write(eq(Topics.ORDER_EVENTS), eq(EventTypes.ORDER_REJECTED), eq("Order"),
                eq(order.getId().toString()), any(), eq(event.eventId()));
    }

    @Test
    void duplicateEventIsSkipped() {
        when(processed.markProcessed(any(), any())).thenReturn(false);

        handler.handle(event(EventTypes.INVENTORY_RESERVED, new Events.InventoryReserved(order.getId(), "c-1", BigDecimal.TEN, "USD")));

        verifyNoInteractions(repository, outbox);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void lateEventForCancelledOrderDoesNotCorruptState() {
        order.transitionTo(OrderStatus.CANCELLED, "customer");
        when(processed.markProcessed(any(), any())).thenReturn(true);
        when(repository.findById(order.getId())).thenReturn(Optional.of(order));

        handler.handle(event(EventTypes.INVENTORY_RESERVED, new Events.InventoryReserved(order.getId(), "c-1", BigDecimal.TEN, "USD")));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(outbox, never()).write(any(), any(), any(), any(), any(), any());
        assertThat(metrics.counter("saga.transitions.ignored", "from", "CANCELLED", "to", "INVENTORY_RESERVED").count()).isEqualTo(1);
    }
}
