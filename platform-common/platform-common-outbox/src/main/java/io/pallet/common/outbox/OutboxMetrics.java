package io.pallet.common.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Outbox gauges and counters, named {@code <metrics-prefix>.outbox.*}. */
public class OutboxMetrics {

    public static final String PENDING = "outbox.pending";
    public static final String OLDEST_PENDING_AGE = "outbox.oldest_pending_age_seconds";
    public static final String HELD_BACK = "outbox.held_back";
    public static final String PARKED = "outbox.parked";
    public static final String PUBLISHED = "outbox.published";
    public static final String PUBLISH_FAILURES = "outbox.publish_failures";
    public static final String RELAY_ACTIVE = "outbox.relay.active";

    public static final String TAG_EVENT_TYPE = "eventType";
    public static final String TAG_KIND = "kind";
    public static final String KIND_BROKER = "broker";
    public static final String KIND_ROW = "row";

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    private final OutboxRepository repository;
    private final MeterRegistry registry;
    private final String publishedMetric;
    private final String publishFailuresMetric;
    private final AtomicReference<OutboxStats> stats = new AtomicReference<>(OutboxStats.EMPTY);
    private final AtomicBoolean relayActive = new AtomicBoolean();

    OutboxMetrics(OutboxRepository repository, MeterRegistry registry, OutboxProperties properties) {
        this.repository = repository;
        this.registry = registry;
        this.publishedMetric = properties.metric(PUBLISHED);
        this.publishFailuresMetric = properties.metric(PUBLISH_FAILURES);
        Gauge.builder(properties.metric(PENDING), stats, s -> s.get().pending()).register(registry);
        Gauge.builder(properties.metric(PARKED), stats, s -> s.get().parked()).register(registry);
        Gauge.builder(properties.metric(HELD_BACK), stats, s -> s.get().heldBack())
                .register(registry);
        Gauge.builder(properties.metric(OLDEST_PENDING_AGE), stats, s -> s.get().oldestPendingAgeSeconds())
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder(properties.metric(RELAY_ACTIVE), relayActive, flag -> flag.get() ? 1 : 0)
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${pallet.outbox.metrics-refresh-interval:PT5S}")
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
        registry.counter(publishedMetric, TAG_EVENT_TYPE, eventType).increment();
    }

    void publishFailure(String kind) {
        registry.counter(publishFailuresMetric, TAG_KIND, kind).increment();
    }
}
