package com.acme.orderplatform.common.config;

import com.acme.orderplatform.common.events.EventCodec;
import com.acme.orderplatform.common.events.EventDecodingException;
import com.acme.orderplatform.common.events.Topics;
import com.acme.orderplatform.common.inbox.ProcessedEventStore;
import com.acme.orderplatform.common.outbox.OutboxRelay;
import com.acme.orderplatform.common.outbox.OutboxWriter;
import com.acme.orderplatform.common.tracing.CorrelationIdRecordInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.ArrayList;
import java.util.List;

/**
 * Reliable-messaging building blocks every service gets for free: event codec, outbox writer +
 * relay, idempotent-consumer store, retry with exponential backoff, dead-letter topics and
 * correlation-id propagation through Kafka.
 */
@AutoConfiguration(after = {KafkaAutoConfiguration.class, JdbcTemplateAutoConfiguration.class, TransactionAutoConfiguration.class})
@EnableConfigurationProperties(PlatformProperties.class)
@EnableScheduling
public class PlatformMessagingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PlatformMessagingAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    EventCodec eventCodec(ObjectMapper mapper) {
        return new EventCodec(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxWriter outboxWriter(JdbcTemplate jdbc, EventCodec codec, @Value("${spring.application.name}") String source) {
        return new OutboxWriter(jdbc, codec, source);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "platform.outbox", name = "relay-enabled", havingValue = "true", matchIfMissing = true)
    OutboxRelay outboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, PlatformTransactionManager txManager,
                            PlatformProperties props, MeterRegistry registry) {
        return new OutboxRelay(jdbc, kafka, new TransactionTemplate(txManager), props.outbox().batchSize(), registry);
    }

    @Bean
    @ConditionalOnMissingBean
    ProcessedEventStore processedEventStore(JdbcTemplate jdbc) {
        return new ProcessedEventStore(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    CorrelationIdRecordInterceptor correlationIdRecordInterceptor() {
        return new CorrelationIdRecordInterceptor();
    }

    /**
     * Transient failures (DB blip, lock timeout) are retried in place with exponential backoff.
     * After the retries, or immediately for poison messages, the record is parked on
     * {@code <topic>.DLT} with the exception in its headers, so one bad message never blocks a
     * partition and nothing is silently dropped. Ops can replay from the DLT after a fix.
     */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, PlatformProperties props,
                                          MeterRegistry registry) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + Topics.DLT_SUFFIX, record.partition()));
        PlatformProperties.Kafka k = props.kafka();
        var backOff = new ExponentialBackOff(k.initialBackoffMs(), 2.0);
        backOff.setMaxInterval(k.maxBackoffMs());
        backOff.setMaxAttempts(k.maxRetries());
        var handler = new DefaultErrorHandler((record, ex) -> {
            registry.counter("kafka.records.dead_lettered", "topic", record.topic()).increment();
            log.error("Parking record on DLT topic={} partition={} offset={} key={}: {}",
                    record.topic(), record.partition(), record.offset(), record.key(), ex.getMessage());
            recoverer.accept(record, ex);
        }, backOff);
        handler.addNotRetryableExceptions(EventDecodingException.class, DeserializationException.class,
                ConversionException.class);
        return handler;
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.kafka", name = "create-topics", havingValue = "true", matchIfMissing = true)
    KafkaAdmin.NewTopics platformTopics(PlatformProperties props) {
        List<NewTopic> topics = new ArrayList<>();
        for (String name : Topics.ALL) {
            topics.add(TopicBuilder.name(name).partitions(props.kafka().partitions())
                    .replicas(props.kafka().replicationFactor()).build());
            topics.add(TopicBuilder.name(name + Topics.DLT_SUFFIX).partitions(props.kafka().partitions())
                    .replicas(props.kafka().replicationFactor()).build());
        }
        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }
}
