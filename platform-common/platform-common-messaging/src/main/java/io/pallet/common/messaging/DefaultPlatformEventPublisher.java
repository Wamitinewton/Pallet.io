package io.pallet.common.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.error.ExternalServiceException;
import io.pallet.common.events.EventHeaders;
import io.pallet.common.events.EventType;
import io.pallet.common.events.PlatformEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


public class DefaultPlatformEventPublisher implements PlatformEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DefaultPlatformEventPublisher.class);

    private final KafkaTemplate<String, Object> template;
    private final MeterRegistry meterRegistry;
    private final Duration sendTimeout;

    public DefaultPlatformEventPublisher(KafkaTemplate<String, Object> template,
                                         MeterRegistry meterRegistry,
                                         MessagingProperties properties) {
        this.template = template;
        this.meterRegistry = meterRegistry;
        this.sendTimeout = properties.publish().sendTimeout();
    }

    @Override
    public void publish(PlatformEvent event) {
        publish(EventType.topicFor(event), event);
    }

    @Override
    public void publish(String topic, PlatformEvent event) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, event.orgId(), event);
        record.headers()
                .add(EventHeaders.EVENT_ID, utf8(event.eventId().toString()))
                .add(EventHeaders.EVENT_TYPE, utf8(event.eventType()))
                .add(EventHeaders.OCCURRED_AT, utf8(event.occurredAt().toString()))
                .add(EventHeaders.ORG_ID, utf8(event.orgId()));
        io.pallet.common.observability.CorrelationId.current()
                .ifPresent(id -> record.headers().add(EventHeaders.CORRELATION_ID, utf8(id)));

        try {
            template.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            count(topic, "success");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failed(topic, event, e);
        } catch (ExecutionException | TimeoutException e) {
            throw failed(topic, event, e);
        }
    }

    private ExternalServiceException failed(String topic, PlatformEvent event, Exception cause) {
        count(topic, "failure");
        log.warn("Event publish to {} failed for type={} orgId={} eventId={}", topic, event.eventType(), event.orgId(), event.eventId(), cause);
        return new ExternalServiceException(
                "Event publish failed",
                "topic=" + topic + " type=" + event.eventType(),
                cause);
    }

    private void count(String topic, String outcome) {
        meterRegistry.counter("messaging.published", "topic", topic, "outcome", outcome).increment();
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
