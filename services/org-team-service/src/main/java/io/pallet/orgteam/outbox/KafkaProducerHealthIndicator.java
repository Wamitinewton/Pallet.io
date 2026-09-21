package io.pallet.orgteam.outbox;

import io.pallet.common.events.Topics;
import io.pallet.common.messaging.MessagingProperties;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Ready once the producer has obtained topic metadata. Requests only write Postgres, so a later broker outage must
 * stay visible through the outbox gauges and alerts rather than pulling healthy pods out of rotation.
 */
@Component("kafkaProducer")
class KafkaProducerHealthIndicator implements HealthIndicator, AutoCloseable {

    private static final String PROBED_TOPIC = Topics.ORG_MEMBER_ADDED;

    private final KafkaTemplate<String, Object> template;
    private final Duration timeout;
    private final ExecutorService probes = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean metadataObtained = new AtomicBoolean();

    KafkaProducerHealthIndicator(KafkaTemplate<String, Object> template, MessagingProperties properties) {
        this.template = template;
        this.timeout = properties.publish().sendTimeout();
    }

    @Override
    public Health health() {
        if (metadataObtained.get()) {
            return Health.up().withDetail("metadata", "obtained").build();
        }
        try {
            int partitions = CompletableFuture.supplyAsync(() -> template.partitionsFor(PROBED_TOPIC), probes)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .size();
            metadataObtained.set(true);
            return Health.up().withDetail("partitions", partitions).build();
        } catch (TimeoutException unreachable) {
            return Health.down().withDetail("reason", "metadata timeout").build();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Health.down().withDetail("reason", "interrupted").build();
        } catch (Exception failure) {
            return Health.down()
                    .withDetail("reason", failure.getClass().getSimpleName())
                    .build();
        }
    }

    @Override
    public void close() {
        probes.shutdownNow();
    }
}
