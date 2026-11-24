package com.acme.orderplatform.order.domain;

import com.acme.orderplatform.common.tracing.CorrelationId;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Order aggregate root. All state changes go through {@link #transitionTo} so the rules and the
 * audit trail can't be bypassed. {@code @Version} gives optimistic locking against lost updates.
 */
@Entity
@Table(name = "orders")
public class CustomerOrder {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private List<OrderLine> lines = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    @OrderBy("id")
    private List<OrderStatusChange> history = new ArrayList<>();

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CustomerOrder() {
    }

    public static CustomerOrder place(String customerId, String currency, List<OrderLine> lines,
                                      String idempotencyKey, String requestHash) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one line");
        }
        CustomerOrder order = new CustomerOrder();
        order.id = UUID.randomUUID();
        order.customerId = customerId;
        order.currency = currency;
        order.idempotencyKey = idempotencyKey;
        order.requestHash = requestHash;
        order.lines.addAll(lines);
        order.totalAmount = lines.stream().map(OrderLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        order.status = OrderStatus.PENDING;
        order.createdAt = Instant.now();
        order.updatedAt = order.createdAt;
        order.history.add(new OrderStatusChange(null, OrderStatus.PENDING, "Order placed", CorrelationId.current(), order.createdAt));
        return order;
    }

    public void transitionTo(OrderStatus next, String reason) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidOrderTransitionException(status, next);
        }
        history.add(new OrderStatusChange(status, next, reason, CorrelationId.current(), Instant.now()));
        this.status = next;
        if (next == OrderStatus.REJECTED || next == OrderStatus.CANCELLED) {
            this.failureReason = reason;
        }
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public List<OrderLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

    public List<OrderStatusChange> getHistory() {
        return Collections.unmodifiableList(history);
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
