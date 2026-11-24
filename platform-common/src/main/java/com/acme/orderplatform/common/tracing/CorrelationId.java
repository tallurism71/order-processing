package com.acme.orderplatform.common.tracing;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/** The correlation id lives in the SLF4J MDC so every log line, outbound event and error response carries it. */
public final class CorrelationId {
    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    public static final String KAFKA_HEADER = "correlationId";

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private CorrelationId() {
    }

    public static String current() {
        String id = MDC.get(MDC_KEY);
        return id != null ? id : UUID.randomUUID().toString();
    }

    /** Accept a caller-supplied id only if it is safe to log (prevents log injection); otherwise mint one. */
    public static String sanitizeOrGenerate(String candidate) {
        return candidate != null && SAFE.matcher(candidate).matches() ? candidate : UUID.randomUUID().toString();
    }
}
