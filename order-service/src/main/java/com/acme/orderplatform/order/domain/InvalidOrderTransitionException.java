package com.acme.orderplatform.order.domain;

import com.acme.orderplatform.common.web.BusinessRuleViolationException;
import org.springframework.http.HttpStatus;

public class InvalidOrderTransitionException extends BusinessRuleViolationException {
    public InvalidOrderTransitionException(OrderStatus from, OrderStatus to) {
        super("INVALID_STATE_TRANSITION", "Order cannot move from " + from + " to " + to, HttpStatus.CONFLICT);
    }
}
