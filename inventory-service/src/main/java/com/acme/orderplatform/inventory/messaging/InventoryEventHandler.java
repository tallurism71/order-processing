package com.acme.orderplatform.inventory.messaging;

import com.acme.orderplatform.common.events.EventCodec;
import com.acme.orderplatform.common.events.EventEnvelope;
import com.acme.orderplatform.common.events.EventTypes;
import com.acme.orderplatform.common.events.Events;
import com.acme.orderplatform.common.events.Topics;
import com.acme.orderplatform.common.inbox.ProcessedEventStore;
import com.acme.orderplatform.inventory.service.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class InventoryEventHandler {

    static final String CONSUMER = "inventory-service.orders";
    private static final Logger log = LoggerFactory.getLogger(InventoryEventHandler.class);

    private final EventCodec codec;
    private final ProcessedEventStore processed;
    private final InventoryService inventory;

    public InventoryEventHandler(EventCodec codec, ProcessedEventStore processed, InventoryService inventory) {
        this.codec = codec;
        this.processed = processed;
        this.inventory = inventory;
    }

    @KafkaListener(id = "inventory-orders", groupId = "inventory-service", topics = Topics.ORDER_EVENTS,
            concurrency = "${app.kafka.listener-concurrency:3}")
    @Transactional
    public void onOrderEvent(String message) {
        EventEnvelope event = codec.decode(message);
        if (!processed.markProcessed(event.eventId(), CONSUMER)) {
            log.info("Skipping duplicate event {}", event.eventId());
            return;
        }
        UUID orderId = UUID.fromString(event.aggregateId());
        switch (event.eventType()) {
            case EventTypes.ORDER_CREATED -> inventory.reserve(codec.payload(event, Events.OrderCreated.class), event);
            case EventTypes.ORDER_CANCELLED, EventTypes.ORDER_REJECTED -> inventory.release(orderId);
            case EventTypes.ORDER_CONFIRMED -> inventory.commit(orderId);
            default -> log.debug("Inventory ignores {}", event.eventType());
        }
    }
}
