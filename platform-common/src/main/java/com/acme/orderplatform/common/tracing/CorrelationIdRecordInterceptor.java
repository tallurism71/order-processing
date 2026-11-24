package com.acme.orderplatform.common.tracing;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

import java.nio.charset.StandardCharsets;

/** Restores the correlation id from the Kafka header so consumer-side logs join up with the original request. */
public class CorrelationIdRecordInterceptor implements RecordInterceptor<Object, Object> {

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        Header header = record.headers().lastHeader(CorrelationId.KAFKA_HEADER);
        String id = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        MDC.put(CorrelationId.MDC_KEY, CorrelationId.sanitizeOrGenerate(id));
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        MDC.remove(CorrelationId.MDC_KEY);
    }
}
