package com.acme.orderplatform.common.web;

import com.acme.orderplatform.common.tracing.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One error format for every service: RFC 7807 Problem Details, always with a stable {@code code}
 * and the {@code correlationId} so support can find the matching logs and traces in seconds.
 * Unexpected errors never leak stack traces or SQL to the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_BASE = "https://errors.acme.example/";

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<Object> notFound(ResourceNotFoundException ex, WebRequest request) {
        return problem(ex, HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    ResponseEntity<Object> businessRule(BusinessRuleViolationException ex, WebRequest request) {
        return problem(ex, ex.getStatus(), ex.getCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Object> concurrentUpdate(OptimisticLockingFailureException ex, WebRequest request) {
        return problem(ex, HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The resource was changed by another request; reload and retry", request);
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    ResponseEntity<Object> constraintViolation(jakarta.validation.ConstraintViolationException ex, WebRequest request) {
        return problem(ex, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception ex, WebRequest request) {
        log.error("Unhandled error", ex);
        return problem(ex, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Unexpected error. Quote the correlationId when contacting support.", request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> errors.putIfAbsent(e.getField(), e.getDefaultMessage()));
        ProblemDetail body = ex.getBody();
        body.setProperty("code", "VALIDATION_FAILED");
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /** Raised instead of the above when a controller also has constraints on headers/params (Spring 6.1+). */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getParameterValidationResults().forEach(result -> {
            if (result instanceof ParameterErrors pe) {
                pe.getFieldErrors().forEach(e -> errors.putIfAbsent(e.getField(), e.getDefaultMessage()));
            } else {
                String name = result.getMethodParameter().getParameterName();
                result.getResolvableErrors().forEach(e -> errors.putIfAbsent(name, e.getDefaultMessage()));
            }
        });
        ProblemDetail body = ex.getBody();
        body.setProperty("code", "VALIDATION_FAILED");
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /** Every Problem Detail, including Spring's built-in ones (400, 405, 415...), gets the correlation id. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail pd) {
            pd.setProperty("correlationId", CorrelationId.current());
            if (pd.getProperties() == null || !pd.getProperties().containsKey("code")) {
                pd.setProperty("code", HttpStatus.valueOf(statusCode.value()).name());
            }
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private ResponseEntity<Object> problem(Exception ex, HttpStatus status, String code, String detail, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + code.toLowerCase().replace('_', '-')));
        pd.setProperty("code", code);
        return handleExceptionInternal(ex, pd, new HttpHeaders(), status, request);
    }
}
