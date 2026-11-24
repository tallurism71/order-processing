package com.acme.orderplatform.common.web;

import org.springframework.http.HttpStatus;

/** A request that is well-formed but breaks a business rule. Carries a stable machine-readable code. */
public class BusinessRuleViolationException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public BusinessRuleViolationException(String code, String message) {
        this(code, message, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    public BusinessRuleViolationException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
