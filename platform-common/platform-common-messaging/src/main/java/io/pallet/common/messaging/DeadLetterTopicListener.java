package io.pallet.common.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.events.EventHeaders;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;

/**
 * Consumes every {@code *.DLT} topic, logs the envelope and the failure that put
 * the record there, and increments {@code messaging.dead_letter{topic}}. One
 * shared consumer group so a message is logged once across the platform; a
 * service opts out with {@code pallet.messaging.dlt-monitor.enabled=false}.
 */
public class DeadLetterTopicListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterTopicListener.class);

    private final MeterRegistry meterRegistry;

    public DeadLetterTopicListener(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(topicPattern = ".*\\.DLT", groupId = "pallet-dlt-monitor")
    public void onDeadLetter(ConsumerRecord<String, JsonNode> record,
                             @Header(name = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String failure) {
        JsonNode envelope = record.value();
        log.error("Dead-letter {}-{}@{} eventType={} eventId={} correlationId={} cause={}",
                record.topic(), record.partition(), record.offset(),
                field(envelope, "eventType"), field(envelope, "eventId"),
                header(record, EventHeaders.CORRELATION_ID), failure);
        meterRegistry.counter("messaging.dead_letter", "topic", record.topic()).increment();
    }

    private static String field(JsonNode envelope, String name) {
        return envelope != null && envelope.hasNonNull(name) ? envelope.get(name).asString() : null;
    }

    private static String header(ConsumerRecord<?, ?> record, String key) {
        org.apache.kafka.common.header.Header header = record.headers().lastHeader(key);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
    }
}
