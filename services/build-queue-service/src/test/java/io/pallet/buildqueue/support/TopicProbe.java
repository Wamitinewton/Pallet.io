package io.pallet.buildqueue.support;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Reads a topic from the beginning under a throwaway group; {@code value} is null for a tombstone. */
public final class TopicProbe implements AutoCloseable {

    public record Received(String key, String value, long timestamp, Headers headers) {

        public String header(String name) {
            Header header = headers.lastHeader(name);
            return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        }
    }

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
                received.add(new Received(record.key(), record.value(), record.timestamp(), record.headers()));
            }
        }
        return withKey(key);
    }

    /** Waits for the first record under {@code key} that matches; fails if none arrives in time. */
    public Received awaitFirst(String key, Predicate<Received> matching) {
        long deadline = System.nanoTime() + AWAIT_LIMIT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<Received> found = withKey(key).stream().filter(matching).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            for (ConsumerRecord<String, String> record : consumer.poll(POLL)) {
                received.add(new Received(record.key(), record.value(), record.timestamp(), record.headers()));
            }
        }
        throw new AssertionError("No matching record under key " + key + " within " + AWAIT_LIMIT);
    }

    /** Waits until {@code expected} records under {@code key} match, and returns every match read so far. */
    public List<Received> awaitMatching(String key, Predicate<Received> matching, int expected) {
        long deadline = System.nanoTime() + AWAIT_LIMIT.toNanos();
        while (System.nanoTime() < deadline) {
            List<Received> found = withKey(key).stream().filter(matching).toList();
            if (found.size() >= expected) {
                return found;
            }
            for (ConsumerRecord<String, String> record : consumer.poll(POLL)) {
                received.add(new Received(record.key(), record.value(), record.timestamp(), record.headers()));
            }
        }
        throw new AssertionError(
                "Fewer than " + expected + " matching records under key " + key + " within " + AWAIT_LIMIT);
    }

    private List<Received> withKey(String key) {
        return received.stream().filter(r -> key.equals(r.key())).toList();
    }

    @Override
    public void close() {
        consumer.close(Duration.ofSeconds(2));
    }
}
