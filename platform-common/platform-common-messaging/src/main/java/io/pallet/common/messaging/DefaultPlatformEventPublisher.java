package io.pallet.common.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.error.ExternalServiceException;
import io.pallet.common.events.EventHeaders;
import io.pallet.common.events.EventType;
import io.pallet.common.events.PlatformEvent;
import io.pallet.common.events.Topics;
import io.pallet.common.observability.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

public class DefaultPlatformEventPublisher implements PlatformEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DefaultPlatformEventPublisher.class);

    private final KafkaTemplate<String, Object> template;
    private final MeterRegistry meterRegistry;
    private final Duration sendTimeout;

    public DefaultPlatformEventPublisher(
            KafkaTemplate<String, Object> template, MeterRegistry meterRegistry, MessagingProperties properties) {
        this.template = template;
        this.meterRegistry = meterRegistry;
        this.sendTimeout = properties.publish().sendTimeout();
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void publish(PlatformEvent event) {
        send(EventType.topicFor(event), event.orgId(), event);
    }

    @Override
    public void publish(String topic, PlatformEvent event) {
        send(topic, event.orgId(), event);
    }

    @Override
    public void publish(PlatformEvent event, String key) {
        send(EventType.topicFor(event), requireKey(key), event);
    }

    @Override
    public void publishTombstone(String topic, String key) {
        requireKey(key);
        if (!Topics.isCompacted(topic)) {
            throw new IllegalArgumentException("Tombstones are only valid on a compacted topic: " + topic);
        }
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, null);
        stampCorrelationId(record);
        await(record, () -> "tombstone key=" + key);
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("An explicit record key must not be blank");
        }
        return key;
    }

    private static void stampCorrelationId(ProducerRecord<String, Object> record) {
        CorrelationId.current().ifPresent(id -> record.headers().add(EventHeaders.CORRELATION_ID, utf8(id)));
    }

    private void send(String topic, String key, PlatformEvent event) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, event);
        record.headers()
                .add(EventHeaders.EVENT_ID, utf8(event.eventId().toString()))
                .add(EventHeaders.EVENT_TYPE, utf8(event.eventType()))
                .add(EventHeaders.OCCURRED_AT, utf8(event.occurredAt().toString()))
                .add(EventHeaders.ORG_ID, utf8(event.orgId()));
        stampCorrelationId(record);
        await(record, () -> "type=%s orgId=%s eventId=%s".formatted(event.eventType(), event.orgId(), event.eventId()));
    }

    private void await(ProducerRecord<String, Object> record, Supplier<String> describe) {
        String topic = record.topic();
        try {
            template.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            count(topic, "success");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failed(topic, describe.get(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw failed(topic, describe.get(), e);
        }
    }

    private ExternalServiceException failed(String topic, String description, Exception cause) {
        count(topic, "failure");
        log.warn("Event publish to {} failed for {}", topic, description, cause);
        return new ExternalServiceException("Event publish failed", "topic=" + topic + " " + description, cause);
    }

    private void count(String topic, String outcome) {
        meterRegistry
                .counter("messaging.published", "topic", topic, "outcome", outcome)
                .increment();
    }
}
