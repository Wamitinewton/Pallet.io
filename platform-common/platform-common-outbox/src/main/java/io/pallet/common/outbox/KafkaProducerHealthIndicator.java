package io.pallet.common.outbox;

import io.pallet.common.messaging.MessagingProperties;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Ready once the producer has obtained topic metadata for a topic the service publishes to. Requests only write
 * Postgres, so a later broker outage must stay visible through the outbox gauges and alerts rather than pulling healthy
 * pods out of rotation.
 */
class KafkaProducerHealthIndicator implements HealthIndicator, AutoCloseable {

    private final KafkaTemplate<String, Object> template;
    private final String probedTopic;
    private final Duration timeout;
    private final ExecutorService probes = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean metadataObtained = new AtomicBoolean();
    private CompletableFuture<Integer> inFlight;

    KafkaProducerHealthIndicator(
            KafkaTemplate<String, Object> template, OutboxEventTypes eventTypes, MessagingProperties properties) {
        this.template = template;
        this.probedTopic =
                eventTypes.typeNames().stream().min(Comparator.naturalOrder()).orElse(null);
        this.timeout = properties.publish().sendTimeout();
    }

    @Override
    public Health health() {
        if (probedTopic == null) {
            return Health.up().withDetail("metadata", "no published types").build();
        }
        if (metadataObtained.get()) {
            return Health.up().withDetail("metadata", "obtained").build();
        }
        try {
            int partitions = probe().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            metadataObtained.set(true);
            return Health.up().withDetail("partitions", partitions).build();
        } catch (TimeoutException unreachable) {
            return Health.down().withDetail("reason", "metadata timeout").build();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Health.down().withDetail("reason", "interrupted").build();
        } catch (ExecutionException failure) {
            return Health.down()
                    .withDetail("reason", reasonOf(failure.getCause()))
                    .build();
        }
    }

    private synchronized CompletableFuture<Integer> probe() {
        if (inFlight == null || inFlight.isCompletedExceptionally()) {
            inFlight = CompletableFuture.supplyAsync(
                    () -> template.partitionsFor(probedTopic).size(), probes);
        }
        return inFlight;
    }

    private static String reasonOf(Throwable cause) {
        return cause == null ? "unknown" : cause.getClass().getSimpleName();
    }

    @Override
    public void close() {
        probes.shutdownNow();
    }
}
