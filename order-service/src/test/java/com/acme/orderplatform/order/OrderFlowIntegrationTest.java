package com.acme.orderplatform.order;

import com.acme.orderplatform.common.events.EventCodec;
import com.acme.orderplatform.common.events.EventEnvelope;
import com.acme.orderplatform.common.events.EventTypes;
import com.acme.orderplatform.common.events.Events;
import com.acme.orderplatform.common.events.Topics;
import com.acme.orderplatform.order.api.OrderDtos;
import com.acme.orderplatform.order.domain.OrderStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Full slice of the order service against real Postgres, Redis and Kafka: REST create (idempotent),
 * outbox relay to Kafka, then the saga reacting to inventory + payment events to CONFIRMED.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"platform.outbox.poll-interval-ms=100", "logging.structured.format.console="})
class OrderFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    EventCodec codec;
    @Autowired
    ObjectMapper mapper;

    @Test
    void orderIsCreatedOnceAndConfirmedBySaga() {
        var request = new OrderDtos.CreateOrderRequest("cust-it", "USD",
                List.of(new OrderDtos.LineRequest("SKU-1001", 3, new BigDecimal("12.50"))));
        var headers = new HttpHeaders();
        headers.set("Idempotency-Key", "it-" + UUID.randomUUID());
        headers.set("X-Correlation-Id", "it-correlation-1");

        var first = http.postForEntity("/api/v1/orders", new HttpEntity<>(request, headers), OrderDtos.OrderResponse.class);
        var retry = http.postForEntity("/api/v1/orders", new HttpEntity<>(request, headers), OrderDtos.OrderResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID orderId = first.getBody().id();
        assertThat(retry.getBody().id()).isEqualTo(orderId);
        assertThat(jdbc.queryForObject("select count(*) from orders where customer_id = 'cust-it'", Integer.class)).isEqualTo(1);

        // Outbox row is relayed to Kafka and carries the caller's correlation id.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select count(*) from outbox_event where message_key = ? and published_at is not null and correlation_id = 'it-correlation-1'",
                Integer.class, orderId.toString())).isEqualTo(1));

        publish(Topics.INVENTORY_EVENTS, EventTypes.INVENTORY_RESERVED, orderId,
                new Events.InventoryReserved(orderId, "cust-it", new BigDecimal("37.50"), "USD"));
        publish(Topics.PAYMENT_EVENTS, EventTypes.PAYMENT_AUTHORIZED, orderId,
                new Events.PaymentAuthorized(orderId, UUID.randomUUID(), new BigDecimal("37.50"), "AUTH-1"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var order = http.getForObject("/api/v1/orders/" + orderId, OrderDtos.OrderResponse.class);
            assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        });
        assertThat(jdbc.queryForList("select to_status from order_status_history where order_id = ? order by id",
                String.class, orderId)).containsExactly("PENDING", "INVENTORY_RESERVED", "CONFIRMED");
    }

    private void publish(String topic, String type, UUID orderId, Object payload) {
        var envelope = new EventEnvelope(UUID.randomUUID(), type, 1, "Order", orderId.toString(), "it-correlation-1",
                null, "test", Instant.now(), mapper.valueToTree(payload));
        kafkaTemplate.send(topic, orderId.toString(), codec.encode(envelope)).join();
    }
}
