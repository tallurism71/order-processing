package com.acme.orderplatform.recon.job;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ReconciliationRecord(long jobExecutionId, LocalDate businessDate, UUID orderId, String status,
                                   BigDecimal totalAmount, String currency, Issue issue) {

    public enum Issue {
        NONE,
        /** Non-terminal order older than the SLA: a saga step was lost or is stuck. */
        STUCK_IN_PROGRESS,
        /** Outbox row older than the SLA still not on Kafka: downstream never heard about it. */
        EVENT_NOT_PUBLISHED
    }
}
