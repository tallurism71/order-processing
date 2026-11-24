package com.acme.orderplatform.common.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Polls unpublished outbox rows and sends them to Kafka (at-least-once; consumers are idempotent).
 *
 * <p>{@code FOR UPDATE SKIP LOCKED} lets every pod run the relay concurrently without double-sending
 * or a leader election: each pod claims a disjoint batch. Rows are marked published only after the
 * broker acknowledges (acks=all), so a crash simply means the batch is retried.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final Counter published;
    private final Counter failures;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                       int batchSize, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.batchSize = batchSize;
        this.published = Counter.builder("outbox.events.published").register(registry);
        this.failures = Counter.builder("outbox.relay.failures").register(registry);
        // Backlog is the key SLI for event delivery: alert if it keeps growing.
        Gauge.builder("outbox.events.pending", this, OutboxRelay::pendingCount).register(registry);
    }

    @Scheduled(fixedDelayString = "${platform.outbox.poll-interval-ms:500}")
    public void relay() {
        try {
            Integer sent;
            do {
                sent = tx.execute(status -> publishBatch());
            } while (sent != null && sent == batchSize);
        } catch (RuntimeException e) {
            failures.increment();
            log.warn("Outbox relay batch failed; it will be retried on the next poll: {}", e.getMessage());
        }
    }

    private int publishBatch() {
        List<OutboxRow> rows = jdbc.query("""
                        select id, topic, message_key, event_type, correlation_id, payload::text as payload
                        from outbox_event
                        where published_at is null
                        order by created_at
                        limit ?
                        for update skip locked
                        """,
                (rs, i) -> new OutboxRow(rs.getObject("id", UUID.class), rs.getString("topic"),
                        rs.getString("message_key"), rs.getString("event_type"),
                        rs.getString("correlation_id"), rs.getString("payload")),
                batchSize);

        for (OutboxRow row : rows) {
            ProducerRecord<String, String> record = new ProducerRecord<>(row.topic(), row.key(), row.payload());
            record.headers().add("eventType", row.eventType().getBytes(StandardCharsets.UTF_8));
            record.headers().add("eventId", row.id().toString().getBytes(StandardCharsets.UTF_8));
            if (row.correlationId() != null) {
                record.headers().add("correlationId", row.correlationId().getBytes(StandardCharsets.UTF_8));
            }
            try {
                kafka.send(record).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("Kafka send failed for outbox event " + row.id(), e);
            }
            jdbc.update("update outbox_event set published_at = now() where id = ?", row.id());
            published.increment();
        }
        return rows.size();
    }

    private double pendingCount() {
        try {
            Long n = jdbc.queryForObject("select count(*) from outbox_event where published_at is null", Long.class);
            return n == null ? 0 : n;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private record OutboxRow(UUID id, String topic, String key, String eventType, String correlationId, String payload) {
    }
}
