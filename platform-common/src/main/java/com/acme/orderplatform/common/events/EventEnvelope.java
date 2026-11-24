package com.acme.orderplatform.common.events;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Every message on every topic uses this envelope. The metadata is what makes the flow traceable
 * and auditable end to end, independent of the business payload:
 *
 * <ul>
 *   <li>{@code eventId} - globally unique; consumers use it for idempotency (exactly-once effect).</li>
 *   <li>{@code correlationId} - the id of the original API request, carried through every hop.</li>
 *   <li>{@code causationId} - the eventId that caused this event, so the saga can be reconstructed.</li>
 *   <li>{@code schemaVersion} - lets producers evolve payloads without breaking consumers.</li>
 *   <li>{@code source}/{@code occurredAt} - lineage and ordering for audit and reconciliation.</li>
 * </ul>
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int schemaVersion,
        String aggregateType,
        String aggregateId,
        String correlationId,
        UUID causationId,
        String source,
        Instant occurredAt,
        JsonNode payload) {
}
