package com.acme.orderplatform.order.messaging;

import com.acme.orderplatform.common.events.EventCodec;
import com.acme.orderplatform.common.events.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderSagaListener {

    private final EventCodec codec;
    private final OrderSagaHandler handler;

    public OrderSagaListener(EventCodec codec, OrderSagaHandler handler) {
        this.codec = codec;
        this.handler = handler;
    }

    /** Concurrency is set per pod in config; total parallelism is capped by the topic partition count. */
    @KafkaListener(id = "order-saga", groupId = "order-service",
            topics = {Topics.INVENTORY_EVENTS, Topics.PAYMENT_EVENTS},
            concurrency = "${app.kafka.listener-concurrency:3}")
    public void onEvent(String message) {
        handler.handle(codec.decode(message));
    }
}
