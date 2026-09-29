package io.pallet.common.outbox.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.pallet.common.outbox.OutboxEventTypes;
import io.pallet.common.outbox.ProducerHealthProbes;
import io.pallet.common.outbox.ProducerHealthProbes.Probe;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

class ProducerReadinessChaosIntegrationTest extends OutboxChaosSupport {

    private static final Duration PROBE_TIMEOUT = Duration.ofMillis(500);
    private static final int CALLS_DURING_OUTAGE = 4;

    @Autowired
    private OutboxEventTypes eventTypes;

    @Test
    void aColdProducerIsNotReadyDuringAnOutageWithoutStackingProbesAndBecomesReadyOnRecovery() {
        DefaultKafkaProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG,
                30_000));
        CountingTemplate template = new CountingTemplate(factory);
        try (Probe probe = ProducerHealthProbes.create(template, eventTypes, PROBE_TIMEOUT)) {
            try (Fault ignored = track(broker.pause())) {
                for (int call = 0; call < CALLS_DURING_OUTAGE; call++) {
                    Health health = probe.health();
                    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(health.getDetails()).containsEntry("reason", "metadata timeout");
                }
                assertThat(template.metadataRequests())
                        .as("one blocked metadata request, not one per health check")
                        .isOne();
            }

            await().atMost(WAIT)
                    .pollInterval(Duration.ofMillis(250))
                    .untilAsserted(() -> assertThat(probe.health().getStatus()).isEqualTo(Status.UP));
            assertThat(probe.health().getDetails()).containsEntry("metadata", "obtained");
        } finally {
            factory.destroy();
        }
    }

    private static final class CountingTemplate extends KafkaTemplate<String, Object> {

        private final AtomicInteger metadataRequests = new AtomicInteger();

        CountingTemplate(DefaultKafkaProducerFactory<String, Object> factory) {
            super(factory);
        }

        @Override
        public List<PartitionInfo> partitionsFor(String topic) {
            metadataRequests.incrementAndGet();
            return super.partitionsFor(topic);
        }

        int metadataRequests() {
            return metadataRequests.get();
        }
    }
}
