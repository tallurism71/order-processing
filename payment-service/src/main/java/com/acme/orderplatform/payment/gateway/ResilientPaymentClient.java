package com.acme.orderplatform.payment.gateway;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Resilience boundary around the provider. Retry handles blips; the circuit breaker stops hammering
 * a provider that is down and fails fast, which protects our consumer threads and DB connections.
 * When the breaker is open the fallback throws, so the Kafka error handler retries with backoff and
 * finally parks the event on the DLT: no payment is silently skipped.
 */
@Component
public class ResilientPaymentClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientPaymentClient.class);

    private final PaymentGateway gateway;

    public ResilientPaymentClient(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    @CircuitBreaker(name = "paymentGateway", fallbackMethod = "unavailable")
    @Retry(name = "paymentGateway")
    public PaymentGateway.Result authorize(UUID orderId, String customerId, BigDecimal amount, String currency) {
        return gateway.authorize(orderId, customerId, amount, currency);
    }

    @SuppressWarnings("unused")
    private PaymentGateway.Result unavailable(UUID orderId, String customerId, BigDecimal amount, String currency, Throwable t) {
        log.warn("Payment gateway unavailable for order {}: {}", orderId, t.toString());
        throw new GatewayUnavailableException("Payment gateway unavailable: " + t.getMessage());
    }
}
