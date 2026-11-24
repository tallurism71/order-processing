package com.acme.orderplatform.payment.messaging;

import com.acme.orderplatform.common.events.EventCodec;
import com.acme.orderplatform.common.events.EventEnvelope;
import com.acme.orderplatform.common.events.EventTypes;
import com.acme.orderplatform.common.events.Events;
import com.acme.orderplatform.common.events.Topics;
import com.acme.orderplatform.common.inbox.ProcessedEventStore;
import com.acme.orderplatform.common.outbox.OutboxWriter;
import com.acme.orderplatform.payment.domain.Payment;
import com.acme.orderplatform.payment.domain.PaymentRepository;
import com.acme.orderplatform.payment.gateway.PaymentGateway;
import com.acme.orderplatform.payment.gateway.ResilientPaymentClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PaymentEventHandler {

    static final String CONSUMER = "payment-service.inventory";
    private static final Logger log = LoggerFactory.getLogger(PaymentEventHandler.class);

    private final EventCodec codec;
    private final ProcessedEventStore processed;
    private final PaymentRepository payments;
    private final ResilientPaymentClient client;
    private final OutboxWriter outbox;
    private final MeterRegistry metrics;

    public PaymentEventHandler(EventCodec codec, ProcessedEventStore processed, PaymentRepository payments,
                               ResilientPaymentClient client, OutboxWriter outbox, MeterRegistry metrics) {
        this.codec = codec;
        this.processed = processed;
        this.payments = payments;
        this.client = client;
        this.outbox = outbox;
        this.metrics = metrics;
    }

    @KafkaListener(id = "payment-inventory", groupId = "payment-service", topics = Topics.INVENTORY_EVENTS,
            concurrency = "${app.kafka.listener-concurrency:3}")
    @Transactional
    public void onInventoryEvent(String message) {
        EventEnvelope event = codec.decode(message);
        if (!EventTypes.INVENTORY_RESERVED.equals(event.eventType())) {
            return;
        }
        if (!processed.markProcessed(event.eventId(), CONSUMER)) {
            log.info("Skipping duplicate event {}", event.eventId());
            return;
        }
        var reserved = codec.payload(event, Events.InventoryReserved.class);
        // Second line of defence: one payment per order, enforced by a unique constraint too.
        if (payments.findByOrderId(reserved.orderId()).isPresent()) {
            log.info("Payment for order {} already exists", reserved.orderId());
            return;
        }

        // orderId doubles as the provider idempotency key: a redelivered event can't charge twice.
        PaymentGateway.Result result = client.authorize(reserved.orderId(), reserved.customerId(),
                reserved.totalAmount(), reserved.currency());

        String orderId = reserved.orderId().toString();
        if (result.approved()) {
            Payment p = payments.save(Payment.authorized(reserved.orderId(), reserved.totalAmount(), reserved.currency(), result.reference()));
            outbox.write(Topics.PAYMENT_EVENTS, EventTypes.PAYMENT_AUTHORIZED, "Order", orderId,
                    new Events.PaymentAuthorized(reserved.orderId(), p.getId(), p.getAmount(), p.getGatewayReference()), event.eventId());
            metrics.counter("payments", "result", "authorized").increment();
        } else {
            payments.save(Payment.failed(reserved.orderId(), reserved.totalAmount(), reserved.currency(), result.declineReason()));
            outbox.write(Topics.PAYMENT_EVENTS, EventTypes.PAYMENT_FAILED, "Order", orderId,
                    new Events.PaymentFailed(reserved.orderId(), result.declineReason()), event.eventId());
            metrics.counter("payments", "result", "declined").increment();
        }
        log.info("Payment for order {}: {}", orderId, result.approved() ? "authorized" : "declined - " + result.declineReason());
    }
}
