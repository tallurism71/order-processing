package com.acme.orderplatform.order.service;

import com.acme.orderplatform.common.events.EventTypes;
import com.acme.orderplatform.common.events.Topics;
import com.acme.orderplatform.common.outbox.OutboxWriter;
import com.acme.orderplatform.common.web.BusinessRuleViolationException;
import com.acme.orderplatform.order.api.OrderDtos;
import com.acme.orderplatform.order.domain.CustomerOrder;
import com.acme.orderplatform.order.domain.OrderLine;
import com.acme.orderplatform.order.domain.OrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceTest {

    private final OrderRepository repository = mock(OrderRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private OrderService service;

    private final OrderDtos.CreateOrderRequest request = new OrderDtos.CreateOrderRequest("c-1", "USD",
            List.of(new OrderDtos.LineRequest("SKU-1001", 2, new BigDecimal("10.00"))));

    @BeforeEach
    void setUp() {
        service = new OrderService(repository, outbox, mock(OrderCache.class), mock(PlatformTransactionManager.class), mapper, metrics);
    }

    @Test
    void newOrderIsSavedAndOrderCreatedEventWrittenToOutbox() {
        when(repository.findByCustomerIdAndIdempotencyKey("c-1", "key-1")).thenReturn(Optional.empty());

        OrderService.Creation result = service.placeOrder(request, "key-1");

        assertThat(result.created()).isTrue();
        assertThat(result.order().totalAmount()).isEqualByComparingTo("20.00");
        verify(repository).saveAndFlush(any(CustomerOrder.class));
        verify(outbox).write(eq(Topics.ORDER_EVENTS), eq(EventTypes.ORDER_CREATED), eq("Order"),
                eq(result.order().id().toString()), any(), isNull());
        assertThat(metrics.counter("orders.placed", "currency", "USD").count()).isEqualTo(1);
    }

    @Test
    void sameKeySameBodyReplaysOriginalOrderWithoutNewEvent() {
        CustomerOrder existing = CustomerOrder.place("c-1", "USD", List.of(new OrderLine("SKU-1001", 2, new BigDecimal("10.00"))),
                "key-1", RequestFingerprint.of(mapper, request));
        when(repository.findByCustomerIdAndIdempotencyKey("c-1", "key-1")).thenReturn(Optional.of(existing));

        OrderService.Creation result = service.placeOrder(request, "key-1");

        assertThat(result.created()).isFalse();
        assertThat(result.order().id()).isEqualTo(existing.getId());
        verify(repository, never()).saveAndFlush(any());
        verify(outbox, never()).write(any(), any(), any(), any(), any(), any());
    }

    @Test
    void sameKeyDifferentBodyIsRejected() {
        CustomerOrder existing = CustomerOrder.place("c-1", "USD", List.of(new OrderLine("SKU-9", 1, BigDecimal.TEN)),
                "key-1", "a-different-hash");
        when(repository.findByCustomerIdAndIdempotencyKey("c-1", "key-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.placeOrder(request, "key-1"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Idempotency-Key");
    }

    @Test
    void concurrentDuplicateResolvesToTheWinningOrder() {
        CustomerOrder winner = CustomerOrder.place("c-1", "USD", List.of(new OrderLine("SKU-1001", 2, new BigDecimal("10.00"))),
                "key-1", RequestFingerprint.of(mapper, request));
        when(repository.findByCustomerIdAndIdempotencyKey("c-1", "key-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_orders_customer_idempotency"));

        OrderService.Creation result = service.placeOrder(request, "key-1");

        assertThat(result.created()).isFalse();
        assertThat(result.order().id()).isEqualTo(winner.getId());
    }
}
