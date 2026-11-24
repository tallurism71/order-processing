package com.acme.orderplatform.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform")
public record PlatformProperties(Outbox outbox, Kafka kafka) {

    public PlatformProperties {
        outbox = outbox == null ? new Outbox(true, 100, 500) : outbox;
        kafka = kafka == null ? new Kafka(true, 6, (short) 1, 4, 500, 10_000) : kafka;
    }

    public record Outbox(boolean relayEnabled, int batchSize, long pollIntervalMs) {
    }

    /**
     * @param createTopics  create topics on startup (dev). In AWS MSK topics are provisioned by Terraform.
     * @param partitions    partition count = max consumer parallelism per consumer group.
     * @param maxRetries    in-place retries before a record is parked on the dead-letter topic.
     */
    public record Kafka(boolean createTopics, int partitions, short replicationFactor,
                        int maxRetries, long initialBackoffMs, long maxBackoffMs) {
    }
}
