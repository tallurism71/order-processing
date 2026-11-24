package com.acme.orderplatform.common.events;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Event payload contracts (schema version 1). Additive changes only; breaking changes get a new type or topic version. */
public final class Events {

    public record OrderCreated(UUID orderId, String customerId, BigDecimal totalAmount, String currency, List<Line> lines) {
        public record Line(String sku, int quantity, BigDecimal unitPrice) {
        }
    }

    public record OrderCancelled(UUID orderId, String reason) {
    }

    public record OrderConfirmed(UUID orderId) {
    }

    public record OrderRejected(UUID orderId, String reason) {
    }

    public record InventoryReserved(UUID orderId, String customerId, BigDecimal totalAmount, String currency) {
    }

    public record InventoryRejected(UUID orderId, String reason) {
    }

    public record PaymentAuthorized(UUID orderId, UUID paymentId, BigDecimal amount, String gatewayReference) {
    }

    public record PaymentFailed(UUID orderId, String reason) {
    }

    private Events() {
    }
}
