package com.acme.orderplatform.order.service;

import com.acme.orderplatform.common.events.EventTypes;
import com.acme.orderplatform.common.events.Events;
import com.acme.orderplatform.common.events.Topics;
import com.acme.orderplatform.common.outbox.OutboxWriter;
import com.acme.orderplatform.common.web.BusinessRuleViolationException;
import com.acme.orderplatform.common.web.ResourceNotFoundException;
import com.acme.orderplatform.order.api.OrderDtos;
import com.acme.orderplatform.order.api.OrderMapper;
import com.acme.orderplatform.order.domain.CustomerOrder;
import com.acme.orderplatform.order.domain.OrderLine;
import com.acme.orderplatform.order.domain.OrderRepository;
import com.acme.orderplatform.order.domain.OrderStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    public record Creation(OrderDtos.OrderResponse order, boolean created) {
    }

    private final OrderRepository repository;
    private final OutboxWriter outbox;
    private final OrderCache cache;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;

    public OrderService(OrderRepository repository, OutboxWriter outbox, OrderCache cache,
                        PlatformTransactionManager txManager, ObjectMapper mapper, MeterRegistry metrics) {
        this.repository = repository;
        this.outbox = outbox;
        this.cache = cache;
        this.tx = new TransactionTemplate(txManager);
        this.mapper = mapper;
        this.metrics = metrics;
    }

    /**
     * Idempotent create. The (customerId, idempotencyKey) unique constraint is the source of truth,
     * so two concurrent retries of the same request can never create two orders, even across pods.
     */
    public Creation placeOrder(OrderDtos.CreateOrderRequest request, String idempotencyKey) {
        String fingerprint = RequestFingerprint.of(mapper, request);
        try {
            Creation result = tx.execute(status -> repository
                    .findByCustomerIdAndIdempotencyKey(request.customerId(), idempotencyKey)
                    .map(existing -> replay(existing, fingerprint))
                    .orElseGet(() -> create(request, idempotencyKey, fingerprint)));
            if (result.created()) {
                metrics.counter("orders.placed", "currency", request.currency()).increment();
                log.info("Order {} placed for customer {} total={} {}", result.order().id(), request.customerId(),
                        result.order().totalAmount(), request.currency());
            }
            return result;
        } catch (DataIntegrityViolationException concurrentDuplicate) {
            // Another pod inserted the same key between our lookup and insert; return its order.
            return tx.execute(status -> repository
                    .findByCustomerIdAndIdempotencyKey(request.customerId(), idempotencyKey)
                    .map(existing -> replay(existing, fingerprint))
                    .orElseThrow(() -> concurrentDuplicate));
        }
    }

    private Creation create(OrderDtos.CreateOrderRequest request, String idempotencyKey, String fingerprint) {
        var lines = request.lines().stream().map(l -> new OrderLine(l.sku(), l.quantity(), l.unitPrice())).toList();
        CustomerOrder order = CustomerOrder.place(request.customerId(), request.currency(), lines, idempotencyKey, fingerprint);
        repository.saveAndFlush(order);

        var event = new Events.OrderCreated(order.getId(), order.getCustomerId(), order.getTotalAmount(), order.getCurrency(),
                request.lines().stream().map(l -> new Events.OrderCreated.Line(l.sku(), l.quantity(), l.unitPrice())).toList());
        outbox.write(Topics.ORDER_EVENTS, EventTypes.ORDER_CREATED, "Order", order.getId().toString(), event, null);
        return new Creation(OrderMapper.toResponse(order), true);
    }

    private Creation replay(CustomerOrder existing, String fingerprint) {
        if (!existing.getRequestHash().equals(fingerprint)) {
            throw new BusinessRuleViolationException("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different request");
        }
        metrics.counter("orders.idempotent_replays").increment();
        return new Creation(OrderMapper.toResponse(existing), false);
    }

    @Cacheable(cacheNames = OrderCache.ORDERS, key = "#id")
    @Transactional(readOnly = true)
    public OrderDtos.OrderResponse getOrder(UUID id) {
        return repository.findWithLinesById(id)
                .map(OrderMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Order", id));
    }

    @Transactional(readOnly = true)
    public OrderDtos.PageResponse<OrderDtos.OrderResponse> listOrders(String customerId, OrderStatus status, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<CustomerOrder> result = status == null
                ? repository.findByCustomerId(customerId, pageable)
                : repository.findByCustomerIdAndStatus(customerId, status, pageable);
        return new OrderDtos.PageResponse<>(result.map(OrderMapper::toResponse).getContent(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public OrderDtos.OrderResponse cancelOrder(UUID id, String reason) {
        CustomerOrder order = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Order", id));
        order.transitionTo(OrderStatus.CANCELLED, reason);
        outbox.write(Topics.ORDER_EVENTS, EventTypes.ORDER_CANCELLED, "Order", id.toString(),
                new Events.OrderCancelled(id, reason), null);
        cache.evictAfterCommit(id);
        metrics.counter("orders.status.transitions", "to", OrderStatus.CANCELLED.name()).increment();
        return OrderMapper.toResponse(order);
    }
}
