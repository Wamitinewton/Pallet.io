package io.pallet.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DeadLetterTopicListenerTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final DeadLetterTopicListener listener = new DeadLetterTopicListener(meterRegistry);
    private final JsonMapper json = JsonMapper.builder().build();

    private static ConsumerRecord<String, JsonNode> record(String topic, JsonNode value) {
        return new ConsumerRecord<>(topic, 0, 0L, "org_1", value);
    }

    @Test
    void countsEveryDeadLetterByItsTopic() {
        listener.onDeadLetter(
                record("build.failed.DLT", json.readTree("{\"eventType\":\"build.failed\",\"eventId\":\"e-1\"}")),
                "boom");
        listener.onDeadLetter(
                record("build.failed.DLT", json.readTree("{\"eventType\":\"build.failed\",\"eventId\":\"e-2\"}")),
                "boom");

        assertThat(meterRegistry
                        .get("messaging.dead_letter")
                        .tag("topic", "build.failed.DLT")
                        .counter()
                        .count())
                .isEqualTo(2.0);
    }

    @Test
    void toleratesANonDeserializableEnvelope() {
        assertThatCode(() -> listener.onDeadLetter(record("notification.requested.DLT", null), "parse failure"))
                .doesNotThrowAnyException();
        assertThat(meterRegistry
                        .get("messaging.dead_letter")
                        .tag("topic", "notification.requested.DLT")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }
}
