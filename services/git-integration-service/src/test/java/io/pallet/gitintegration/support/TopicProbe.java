package io.pallet.gitintegration.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Reads a topic from the beginning under a throwaway group; {@code value} is null for a tombstone. */
public final class TopicProbe implements AutoCloseable {

    public record Received(String key, String value) {}

    private static final Duration AWAIT_LIMIT = Duration.ofSeconds(30);
    private static final Duration POLL = Duration.ofMillis(200);

    private final KafkaConsumer<String, String> consumer;
    private final List<Received> received = new ArrayList<>();

    public TopicProbe(String bootstrapServers, String topic) {
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
        consumer.subscribe(List.of(topic));
    }

    public List<Received> awaitKey(String key, int expected) {
        long deadline = System.nanoTime() + AWAIT_LIMIT.toNanos();
        while (withKey(key).size() < expected && System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(POLL)) {
                received.add(new Received(record.key(), record.value()));
            }
        }
        return withKey(key);
    }

    private List<Received> withKey(String key) {
        return received.stream().filter(r -> key.equals(r.key())).toList();
    }

    @Override
    public void close() {
        consumer.close(Duration.ofSeconds(2));
    }
}
