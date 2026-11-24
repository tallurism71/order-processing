package com.acme.orderplatform.recon.job;

import org.springframework.batch.item.ItemProcessor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

/** Pure function, so it is trivially unit-testable: classify each order against the completeness rules. */
public class ReconciliationProcessor implements ItemProcessor<OrderSnapshot, ReconciliationRecord> {

    private static final Set<String> TERMINAL = Set.of("CONFIRMED", "REJECTED", "CANCELLED");

    private final long jobExecutionId;
    private final LocalDate businessDate;
    private final Duration sla;
    private final Clock clock;

    public ReconciliationProcessor(long jobExecutionId, LocalDate businessDate, Duration sla, Clock clock) {
        this.jobExecutionId = jobExecutionId;
        this.businessDate = businessDate;
        this.sla = sla;
        this.clock = clock;
    }

    @Override
    public ReconciliationRecord process(OrderSnapshot o) {
        Instant cutoff = clock.instant().minus(sla);
        ReconciliationRecord.Issue issue = ReconciliationRecord.Issue.NONE;
        if (o.unpublishedEvents() > 0 && o.updatedAt().isBefore(cutoff)) {
            issue = ReconciliationRecord.Issue.EVENT_NOT_PUBLISHED;
        } else if (!TERMINAL.contains(o.status()) && o.updatedAt().isBefore(cutoff)) {
            issue = ReconciliationRecord.Issue.STUCK_IN_PROGRESS;
        }
        return new ReconciliationRecord(jobExecutionId, businessDate, o.orderId(), o.status(), o.totalAmount(), o.currency(), issue);
    }
}
