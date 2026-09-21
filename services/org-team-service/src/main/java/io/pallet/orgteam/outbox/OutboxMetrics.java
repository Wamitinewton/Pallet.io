package io.pallet.orgteam.outbox;

import static io.pallet.orgteam.observability.MetricsCatalog.*;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    private final OutboxRepository repository;
    private final MeterRegistry registry;
    private final AtomicReference<OutboxStats> stats = new AtomicReference<>(OutboxStats.EMPTY);
    private final AtomicBoolean relayActive = new AtomicBoolean();

    OutboxMetrics(OutboxRepository repository, MeterRegistry registry) {
        this.repository = repository;
        this.registry = registry;
        Gauge.builder(OUTBOX_PENDING, stats, s -> s.get().pending()).register(registry);
        Gauge.builder(OUTBOX_PARKED, stats, s -> s.get().parked()).register(registry);
        Gauge.builder(OUTBOX_OLDEST_PENDING_AGE, stats, s -> s.get().oldestPendingAgeSeconds())
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder(OUTBOX_RELAY_ACTIVE, relayActive, flag -> flag.get() ? 1 : 0)
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${pallet.orgteam.outbox.metrics-refresh-interval:PT5S}")
    public void refresh() {
        try {
            stats.set(repository.stats());
        } catch (RuntimeException failure) {
            log.warn("Outbox gauges not refreshed: {}", failure.getClass().getSimpleName());
        }
    }

    public OutboxStats current() {
        return stats.get();
    }

    void relayActive(boolean active) {
        relayActive.set(active);
    }

    void published(String eventType) {
        registry.counter(OUTBOX_PUBLISHED, TAG_EVENT_TYPE, eventType).increment();
    }

    void publishFailure(String kind) {
        registry.counter(OUTBOX_PUBLISH_FAILURES, TAG_KIND, kind).increment();
    }
}
