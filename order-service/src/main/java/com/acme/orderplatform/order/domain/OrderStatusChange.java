package com.acme.orderplatform.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Append-only audit trail of every status change, with the correlation id that caused it. */
@Entity
@Table(name = "order_status_history")
public class OrderStatusChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 32)
    private OrderStatus toStatus;

    @Column(length = 255)
    private String reason;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    protected OrderStatusChange() {
    }

    OrderStatusChange(OrderStatus from, OrderStatus to, String reason, String correlationId, Instant at) {
        this.fromStatus = from;
        this.toStatus = to;
        this.reason = reason;
        this.correlationId = correlationId;
        this.changedAt = at;
    }

    public OrderStatus getFromStatus() {
        return fromStatus;
    }

    public OrderStatus getToStatus() {
        return toStatus;
    }

    public String getReason() {
        return reason;
    }
}
