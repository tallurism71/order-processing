package com.acme.orderplatform.order.domain;

import java.util.EnumSet;
import java.util.Set;

/** Order lifecycle. Allowed transitions are declared in one place so the saga cannot corrupt state. */
public enum OrderStatus {
    PENDING, INVENTORY_RESERVED, CONFIRMED, REJECTED, CANCELLED;

    public Set<OrderStatus> allowedNext() {
        return switch (this) {
            case PENDING -> EnumSet.of(INVENTORY_RESERVED, REJECTED, CANCELLED);
            case INVENTORY_RESERVED -> EnumSet.of(CONFIRMED, REJECTED, CANCELLED);
            case CONFIRMED, REJECTED, CANCELLED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canTransitionTo(OrderStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }
}
