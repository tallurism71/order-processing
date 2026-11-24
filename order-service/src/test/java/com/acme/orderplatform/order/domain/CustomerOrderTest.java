package com.acme.orderplatform.order.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerOrderTest {

    private CustomerOrder newOrder() {
        return CustomerOrder.place("c-1", "USD",
                List.of(new OrderLine("SKU-1", 2, new BigDecimal("10.00")), new OrderLine("SKU-2", 1, new BigDecimal("5.50"))),
                "key-12345678", "hash");
    }

    @Test
    void computesTotalAndStartsPending() {
        CustomerOrder order = newOrder();
        assertThat(order.getTotalAmount()).isEqualByComparingTo("25.50");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getHistory()).hasSize(1);
    }

    @Test
    void happyPathRecordsAuditTrail() {
        CustomerOrder order = newOrder();
        order.transitionTo(OrderStatus.INVENTORY_RESERVED, "reserved");
        order.transitionTo(OrderStatus.CONFIRMED, "paid");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getHistory()).extracting(OrderStatusChange::getToStatus)
                .containsExactly(OrderStatus.PENDING, OrderStatus.INVENTORY_RESERVED, OrderStatus.CONFIRMED);
    }

    @Test
    void terminalStatesCannotChange() {
        CustomerOrder order = newOrder();
        order.transitionTo(OrderStatus.CANCELLED, "customer");
        assertThatThrownBy(() -> order.transitionTo(OrderStatus.CONFIRMED, "late payment"))
                .isInstanceOf(InvalidOrderTransitionException.class);
        assertThat(order.getFailureReason()).isEqualTo("customer");
    }

    @Test
    void rejectsEmptyOrderAndNonPositiveQuantity() {
        assertThatThrownBy(() -> CustomerOrder.place("c", "USD", List.of(), "k", "h")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderLine("S", 0, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
    }
}
