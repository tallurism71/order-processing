package com.acme.orderplatform.common.events;

import java.util.List;

/**
 * One topic per aggregate, versioned in the name. Messages are keyed by orderId so every event for
 * one order lands on the same partition and is consumed in order.
 */
public final class Topics {
    public static final String ORDER_EVENTS = "orders.order-events.v1";
    public static final String INVENTORY_EVENTS = "orders.inventory-events.v1";
    public static final String PAYMENT_EVENTS = "orders.payment-events.v1";
    public static final String DLT_SUFFIX = ".DLT";

    public static final List<String> ALL = List.of(ORDER_EVENTS, INVENTORY_EVENTS, PAYMENT_EVENTS);

    private Topics() {
    }
}
