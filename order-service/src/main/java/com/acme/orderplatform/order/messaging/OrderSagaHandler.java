package com.acme.orderplatform.order.messaging;

import com.acme.orderplatform.common.events.EventCodec;
import com.acme.orderplatform.common.events.EventEnvelope;
import com.acme.orderplatform.common.events.EventTypes;
import com.acme.orderplatform.common.events.Events;
import com.acme.orderplatform.common.events.Topics;
import com.acme.orderplatform.common.inbox.ProcessedEventStore;
import com.acme.orderplatform.common.outbox.OutboxWriter;
import com.acme.orderplatform.order.domain.CustomerOrder;
import com.acme.orderplatform.order.domain.OrderRepository;
import com.acme.orderplatform.order.domain.OrderStatus;
import com.acme.orderplatform.order.service.OrderCache;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Order service is the saga's state owner (choreography). It reacts to inventory and payment
 * outcomes, moves the order through its state machine and emits the next event. Idempotency
 * record, state change and outgoing event commit in ONE transaction.
 */
@Service
public class OrderSagaHandler {

    static final String CONSUMER = "order-service.saga";
    private static final Logger log = LoggerFactory.getLogger(OrderSagaHandler.class);

    private final OrderRepository repository;
    private final ProcessedEventStore processed;
    private final OutboxWriter outbox;
    private final EventCodec codec;
    private final OrderCache cache;
    private final MeterRegistry metrics;

    public OrderSagaHandler(OrderRepository repository, ProcessedEventStore processed, OutboxWriter outbox,
                            EventCodec codec, OrderCache cache, MeterRegistry metrics) {
        this.repository = repository;
        this.processed = processed;
        this.outbox = outbox;
        this.codec = codec;
        this.cache = cache;
        this.metrics = metrics;
    }

    @Transactional
    public void handle(EventEnvelope event) {
        if (!processed.markProcessed(event.eventId(), CONSUMER)) {
            metrics.counter("saga.events.duplicate").increment();
            log.info("Skipping duplicate event {} ({})", event.eventId(), event.eventType());
            return;
        }
        UUID orderId = UUID.fromString(event.aggregateId());
        switch (event.eventType()) {
            case EventTypes.INVENTORY_RESERVED -> apply(orderId, OrderStatus.INVENTORY_RESERVED, "Stock reserved", event);
            case EventTypes.INVENTORY_REJECTED -> apply(orderId, OrderStatus.REJECTED,
                    codec.payload(event, Events.InventoryRejected.class).reason(), event);
            case EventTypes.PAYMENT_AUTHORIZED -> apply(orderId, OrderStatus.CONFIRMED, "Payment authorized", event);
            case EventTypes.PAYMENT_FAILED -> apply(orderId, OrderStatus.REJECTED,
                    codec.payload(event, Events.PaymentFailed.class).reason(), event);
            default -> log.debug("Order saga ignores {}", event.eventType());
        }
    }

    private void apply(UUID orderId, OrderStatus next, String reason, EventEnvelope cause) {
        CustomerOrder order = repository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Event " + cause.eventId() + " references unknown order " + orderId));

        if (!order.getStatus().canTransitionTo(next)) {
            // e.g. customer cancelled while payment was in flight. Never corrupt state; surface it instead.
            metrics.counter("saga.transitions.ignored", "from", order.getStatus().name(), "to", next.name()).increment();
            if (order.getStatus() == OrderStatus.CANCELLED && next == OrderStatus.CONFIRMED) {
                metrics.counter("saga.compensation.required", "type", "refund").increment();
                log.warn("Payment authorized for cancelled order {} - refund required", orderId);
            } else {
                log.warn("Ignoring {} for order {} in terminal/incompatible status {}", cause.eventType(), orderId, order.getStatus());
            }
            return;
        }

        order.transitionTo(next, reason);
        metrics.counter("orders.status.transitions", "to", next.name()).increment();

        String id = orderId.toString();
        switch (next) {
            case CONFIRMED -> outbox.write(Topics.ORDER_EVENTS, EventTypes.ORDER_CONFIRMED, "Order", id,
                    new Events.OrderConfirmed(orderId), cause.eventId());
            // Rejection is the compensation trigger: inventory releases any reserved stock.
            case REJECTED -> outbox.write(Topics.ORDER_EVENTS, EventTypes.ORDER_REJECTED, "Order", id,
                    new Events.OrderRejected(orderId, reason), cause.eventId());
            default -> { }
        }
        cache.evictAfterCommit(orderId);
        log.info("Order {} -> {} ({})", orderId, next, reason);
    }
}
