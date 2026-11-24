package com.acme.orderplatform.common.outbox;

import com.acme.orderplatform.common.events.EventCodec;
import com.acme.orderplatform.common.events.EventEnvelope;
import com.acme.orderplatform.common.tracing.CorrelationId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Transactional outbox. The event row is written in the SAME database transaction as the business
 * change, so we never commit an order without its event or publish an event for a rolled-back order
 * (no dual-write problem). {@link OutboxRelay} ships the rows to Kafka afterwards.
 */
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final EventCodec codec;
    private final String source;

    public OutboxWriter(JdbcTemplate jdbc, EventCodec codec, String source) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.source = source;
    }

    public EventEnvelope write(String topic, String eventType, String aggregateType, String aggregateId,
                               Object payload, UUID causationId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("OutboxWriter must be called inside the business transaction");
        }
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(), eventType, 1, aggregateType, aggregateId,
                CorrelationId.current(), causationId, source, Instant.now(),
                codec.mapper().valueToTree(payload));

        jdbc.update("""
                        insert into outbox_event (id, topic, message_key, event_type, correlation_id, payload, created_at)
                        values (?, ?, ?, ?, ?, ?::jsonb, ?)
                        """,
                envelope.eventId(), topic, aggregateId, eventType, envelope.correlationId(),
                codec.encode(envelope), Timestamp.from(envelope.occurredAt()));
        return envelope;
    }
}
