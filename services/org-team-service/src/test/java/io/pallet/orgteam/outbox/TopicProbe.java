package io.pallet.orgteam.outbox;

import io.pallet.common.events.EventHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class TopicProbe implements AutoCloseable {

    public record Received(String topic, String key, Map<String, String> headers, JsonNode body) {

        public UUID eventId() {
            return UUID.fromString(headers.get(EventHeaders.EVENT_ID));
        }
    }

    private static final Duration AWAIT_LIMIT = Duration.ofSeconds(20);
    private static final Duration POLL = Duration.ofMillis(200);

    private final KafkaConsumer<String, String> consumer;
    private final JsonMapper jsonMapper;
    private final List<Received> received = new ArrayList<>();

    public TopicProbe(String bootstrapServers, JsonMapper jsonMapper, String... topics) {
        this.jsonMapper = jsonMapper;
        this.consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG,
                "probe-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class));
        consumer.subscribe(List.of(topics));
    }

    public List<Received> awaitCount(String orgId, int expected) {
        long deadline = System.nanoTime() + AWAIT_LIMIT.toNanos();
        while (forOrg(orgId).size() < expected && System.nanoTime() < deadline) {
            pollOnce();
        }
        return forOrg(orgId);
    }

    public List<Received> observe(String orgId, Duration window) {
        long deadline = System.nanoTime() + window.toNanos();
        while (System.nanoTime() < deadline) {
            pollOnce();
        }
        return forOrg(orgId);
    }

    private void pollOnce() {
        for (ConsumerRecord<String, String> record : consumer.poll(POLL)) {
            Map<String, String> headers = new HashMap<>();
            for (Header header : record.headers()) {
                headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
            }
            received.add(new Received(record.topic(), record.key(), headers, jsonMapper.readTree(record.value())));
        }
    }

    private List<Received> forOrg(String orgId) {
        return received.stream().filter(r -> orgId.equals(r.key())).toList();
    }

    @Override
    public void close() {
        consumer.close(Duration.ofSeconds(2));
    }
}
