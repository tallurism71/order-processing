package com.acme.orderplatform.common.inbox;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Idempotent-consumer ("inbox") table. Call inside the same transaction as the business update:
 * if the event was already processed the insert is a no-op and the handler skips it, so Kafka
 * redeliveries and outbox retries never apply a change twice.
 */
public class ProcessedEventStore {

    private final JdbcTemplate jdbc;

    public ProcessedEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this is the first time {@code consumer} sees {@code eventId}. */
    public boolean markProcessed(UUID eventId, String consumer) {
        int inserted = jdbc.update("""
                insert into processed_event (event_id, consumer, processed_at)
                values (?, ?, now())
                on conflict do nothing
                """, eventId, consumer);
        return inserted == 1;
    }
}
