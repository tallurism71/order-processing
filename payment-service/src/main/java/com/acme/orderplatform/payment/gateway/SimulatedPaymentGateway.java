package com.acme.orderplatform.payment.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stand-in for a real provider (Stripe, Adyen, an internal ledger...). Declines above a limit and
 * can inject technical failures so the circuit breaker and DLT paths can be demonstrated.
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    private final BigDecimal approvalLimit;
    private final double failureRate;

    public SimulatedPaymentGateway(@Value("${app.payment.approval-limit:5000}") BigDecimal approvalLimit,
                                   @Value("${app.payment.simulated-failure-rate:0.0}") double failureRate) {
        this.approvalLimit = approvalLimit;
        this.failureRate = failureRate;
    }

    @Override
    public Result authorize(UUID idempotencyKey, String customerId, BigDecimal amount, String currency) {
        if (failureRate > 0 && ThreadLocalRandom.current().nextDouble() < failureRate) {
            throw new GatewayUnavailableException("Simulated provider timeout");
        }
        if (amount.compareTo(approvalLimit) > 0) {
            return Result.declined("Amount exceeds authorization limit");
        }
        return Result.approved("AUTH-" + idempotencyKey.toString().substring(0, 8).toUpperCase());
    }
}
