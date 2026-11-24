package com.acme.orderplatform.common.events;

public final class EventTypes {
    public static final String ORDER_CREATED = "OrderCreated";
    public static final String ORDER_CANCELLED = "OrderCancelled";
    public static final String ORDER_CONFIRMED = "OrderConfirmed";
    public static final String ORDER_REJECTED = "OrderRejected";
    public static final String INVENTORY_RESERVED = "InventoryReserved";
    public static final String INVENTORY_REJECTED = "InventoryRejected";
    public static final String PAYMENT_AUTHORIZED = "PaymentAuthorized";
    public static final String PAYMENT_FAILED = "PaymentFailed";

    private EventTypes() {
    }
}
