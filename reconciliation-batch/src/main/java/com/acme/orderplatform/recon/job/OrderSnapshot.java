package com.acme.orderplatform.recon.job;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One order as read from the order database, with the count of its events not yet published. */
public record OrderSnapshot(UUID orderId, String customerId, String status, BigDecimal totalAmount, String currency,
                            Instant createdAt, Instant updatedAt, int unpublishedEvents) {
}
