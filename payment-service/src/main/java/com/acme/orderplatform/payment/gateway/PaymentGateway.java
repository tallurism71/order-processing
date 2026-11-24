package com.acme.orderplatform.payment.gateway;

import java.math.BigDecimal;
import java.util.UUID;

/** Port to the external payment provider. A business decline is a result, not an exception. */
public interface PaymentGateway {

    record Result(boolean approved, String reference, String declineReason) {
        public static Result approved(String reference) {
            return new Result(true, reference, null);
        }

        public static Result declined(String reason) {
            return new Result(false, null, reason);
        }
    }

    /** @param idempotencyKey the provider de-duplicates on this, so a retried call never double-charges. */
    Result authorize(UUID idempotencyKey, String customerId, BigDecimal amount, String currency);
}
