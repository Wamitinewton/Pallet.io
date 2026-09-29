package io.pallet.gitintegration.support;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

/** Consumer-group offsets through the admin API: the signal that a listener has processed and committed a record. */
public final class ConsumerGroups implements AutoCloseable {

    private static final Duration WAIT = Duration.ofSeconds(60);

    private final Admin admin;

    public ConsumerGroups(String bootstrapServers) {
        this.admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
    }

    /** Waits until {@code groupId} has committed every record currently on {@code topic}. */
    public void awaitCaughtUp(String groupId, String topic) {
        Map<TopicPartition, Long> ends = offsets(topic, OffsetSpec.latest());
        await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).until(() -> {
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata()
                    .get();
            return ends.entrySet().stream()
                    .allMatch(end -> end.getValue() == 0
                            || (committed.get(end.getKey()) != null
                                    && committed.get(end.getKey()).offset() >= end.getValue()));
        });
    }

    /** Rewinds {@code groupId} to the earliest retained offset; the group must have no active member. */
    public void resetToEarliest(String groupId, String topic) {
        Map<TopicPartition, OffsetAndMetadata> earliest = offsets(topic, OffsetSpec.earliest()).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> new OffsetAndMetadata(entry.getValue())));
        await().atMost(WAIT)
                .pollInterval(Duration.ofMillis(500))
                .ignoreExceptions()
                .until(() -> {
                    admin.alterConsumerGroupOffsets(groupId, earliest).all().get();
                    return true;
                });
    }

    private Map<TopicPartition, Long> offsets(String topic, OffsetSpec spec) {
        try {
            List<TopicPartition> partitions =
                    admin.describeTopics(List.of(topic)).allTopicNames().get().get(topic).partitions().stream()
                            .map(info -> new TopicPartition(topic, info.partition()))
                            .toList();
            Map<TopicPartition, OffsetSpec> request =
                    partitions.stream().collect(Collectors.toMap(Function.identity(), partition -> spec));
            Map<TopicPartition, ListOffsetsResultInfo> result =
                    admin.listOffsets(request).all().get();
            return result.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey, entry -> entry.getValue().offset()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        admin.close(Duration.ofSeconds(2));
    }
}
