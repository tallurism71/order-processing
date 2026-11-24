package com.acme.orderplatform.order.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<CustomerOrder, UUID> {

    Optional<CustomerOrder> findByCustomerIdAndIdempotencyKey(String customerId, String idempotencyKey);

    @EntityGraph(attributePaths = "lines")
    Optional<CustomerOrder> findWithLinesById(UUID id);

    Page<CustomerOrder> findByCustomerId(String customerId, Pageable pageable);

    Page<CustomerOrder> findByCustomerIdAndStatus(String customerId, OrderStatus status, Pageable pageable);
}
