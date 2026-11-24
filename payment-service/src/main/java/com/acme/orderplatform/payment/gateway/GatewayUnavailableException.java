package com.acme.orderplatform.payment.gateway;

/** Technical failure talking to the provider (timeout, 5xx). Retried; counts toward the circuit breaker. */
public class GatewayUnavailableException extends RuntimeException {
    public GatewayUnavailableException(String message) {
        super(message);
    }
}
