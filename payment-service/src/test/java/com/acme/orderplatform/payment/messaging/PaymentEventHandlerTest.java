package com.acme.orderplatform.payment.messaging;

import com.acme.orderplatform.common.events.EventCodec;
import com.acme.orderplatform.common.events.EventEnvelope;
import com.acme.orderplatform.common.events.EventTypes;
import com.acme.orderplatform.common.events.Events;
import com.acme.orderplatform.common.inbox.ProcessedEventStore;
import com.acme.orderplatform.common.outbox.OutboxWriter;
import com.acme.orderplatform.payment.domain.Payment;
import com.acme.orderplatform.payment.domain.PaymentRepository;
import com.acme.orderplatform.payment.gateway.PaymentGateway;
import com.acme.orderplatform.payment.gateway.ResilientPaymentClient;
import com.acme.orderplatform.payment.gateway.SimulatedPaymentGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentEventHandlerTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final EventCodec codec = new EventCodec(mapper);
    private final ProcessedEventStore processed = mock(ProcessedEventStore.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final PaymentGateway gateway = new SimulatedPaymentGateway(new BigDecimal("5000"), 0.0);
    private final PaymentEventHandler handler = new PaymentEventHandler(codec, processed, payments,
            new ResilientPaymentClient(gateway), outbox, new SimpleMeterRegistry());

    private String inventoryReserved(UUID orderId, String amount) {
        var payload = new Events.InventoryReserved(orderId, "c-1", new BigDecimal(amount), "USD");
        return codec.encode(new EventEnvelope(UUID.randomUUID(), EventTypes.INVENTORY_RESERVED, 1, "Order",
                orderId.toString(), "corr", null, "inventory-service", Instant.now(), mapper.valueToTree(payload)));
    }

    @Test
    void authorizesWithinLimit() {
        UUID orderId = UUID.randomUUID();
        when(processed.markProcessed(any(), any())).thenReturn(true);
        when(payments.findByOrderId(orderId)).thenReturn(Optional.empty());
        when(payments.save(any())).thenAnswer(i -> i.getArgument(0));

        handler.onInventoryEvent(inventoryReserved(orderId, "120.00"));

        verify(outbox).write(any(), eq(EventTypes.PAYMENT_AUTHORIZED), any(), eq(orderId.toString()), any(), any());
    }

    @Test
    void declinesAboveLimitAsBusinessOutcomeNotError() {
        UUID orderId = UUID.randomUUID();
        when(processed.markProcessed(any(), any())).thenReturn(true);
        when(payments.findByOrderId(orderId)).thenReturn(Optional.empty());
        when(payments.save(any())).thenAnswer(i -> i.getArgument(0));

        handler.onInventoryEvent(inventoryReserved(orderId, "9999.00"));

        verify(outbox).write(any(), eq(EventTypes.PAYMENT_FAILED), any(), eq(orderId.toString()), any(), any());
    }

    @Test
    void neverChargesTwiceForTheSameOrder() {
        UUID orderId = UUID.randomUUID();
        when(processed.markProcessed(any(), any())).thenReturn(true);
        when(payments.findByOrderId(orderId)).thenReturn(Optional.of(Payment.authorized(orderId, BigDecimal.TEN, "USD", "AUTH-1")));

        handler.onInventoryEvent(inventoryReserved(orderId, "10.00"));

        verify(payments, never()).save(any());
        verify(outbox, never()).write(any(), any(), any(), any(), any(), any());
        assertThat(orderId).isNotNull();
    }
}
