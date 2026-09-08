package io.pallet.common.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Every messaging tunable. The whole block is optional — the defaults match
 * OkoaTiko's proven values (4 delivery attempts, 1s initial backoff, ×2, 30s cap,
 * consumer concurrency 3).
 */
@ConfigurationProperties("pallet.messaging")
public record MessagingProperties(
    @DefaultValue("3") int consumerConcurrency,
    @DefaultValue Retry retry,
    @DefaultValue Publish publish,
    @DefaultValue("true") boolean createTopics,
    @DefaultValue("1") short topicReplicas,
    @DefaultValue("3") int topicPartitions,
    @DefaultValue DltMonitor dltMonitor) {

    /**
     * Consumer retry policy. {@code maxAttempts} is total deliveries; on the last failure the record is dead-lettered.
     */
    public record Retry(
        @DefaultValue("4") int maxAttempts,
        @DefaultValue("1s") Duration initialInterval,
        @DefaultValue("2.0") double multiplier,
        @DefaultValue("30s") Duration maxInterval) {
    }

    /**
     * Producer send policy. The producer is idempotent with {@code acks=all}; this only bounds the wait for the ack.
     */
    public record Publish(
        @DefaultValue("10s") Duration sendTimeout) {
    }

    public record DltMonitor(
        @DefaultValue("true") boolean enabled) {
    }
}
