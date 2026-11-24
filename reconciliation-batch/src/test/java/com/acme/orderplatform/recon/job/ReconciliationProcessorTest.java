package com.acme.orderplatform.recon.job;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationProcessorTest {

    private final Instant now = Instant.parse("2026-11-24T02:00:00Z");
    private final ReconciliationProcessor processor = new ReconciliationProcessor(7L, LocalDate.parse("2026-11-23"),
            Duration.ofMinutes(30), Clock.fixed(now, ZoneOffset.UTC));

    private OrderSnapshot order(String status, Duration age, int unpublished) {
        Instant t = now.minus(age);
        return new OrderSnapshot(UUID.randomUUID(), "c-1", status, new BigDecimal("10.00"), "USD", t, t, unpublished);
    }

    @Test
    void completedOrderIsClean() {
        assertThat(processor.process(order("CONFIRMED", Duration.ofHours(5), 0)).issue())
                .isEqualTo(ReconciliationRecord.Issue.NONE);
    }

    @Test
    void oldNonTerminalOrderIsFlaggedAsStuck() {
        assertThat(processor.process(order("INVENTORY_RESERVED", Duration.ofHours(2), 0)).issue())
                .isEqualTo(ReconciliationRecord.Issue.STUCK_IN_PROGRESS);
    }

    @Test
    void inFlightOrderWithinSlaIsNotFlagged() {
        assertThat(processor.process(order("PENDING", Duration.ofMinutes(5), 1)).issue())
                .isEqualTo(ReconciliationRecord.Issue.NONE);
    }

    @Test
    void unpublishedEventPastSlaIsFlagged() {
        var record = processor.process(order("CONFIRMED", Duration.ofHours(1), 1));
        assertThat(record.issue()).isEqualTo(ReconciliationRecord.Issue.EVENT_NOT_PUBLISHED);
        assertThat(record.jobExecutionId()).isEqualTo(7L);
    }
}
