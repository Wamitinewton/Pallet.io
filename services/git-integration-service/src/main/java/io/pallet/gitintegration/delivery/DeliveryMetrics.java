package io.pallet.gitintegration.delivery;

import static io.pallet.gitintegration.observability.MetricsCatalog.*;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.gitintegration.delivery.DeliveryRepository.DeliveryStats;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every tag value is an event from {@link SubscribedEvents} or a failure kind. */
@Component
public class DeliveryMetrics {

    public static final String KIND_MALFORMED = "malformed";
    public static final String KIND_RATE_LIMITED = "rate_limited";
    public static final String KIND_GITHUB_UNAVAILABLE = "github_unavailable";
    public static final String KIND_ERROR = "error";

    private static final Logger log = LoggerFactory.getLogger(DeliveryMetrics.class);

    private final MeterRegistry registry;
    private final DeliveryRepository deliveries;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(new Snapshot(0, 0, 0));

    DeliveryMetrics(MeterRegistry registry, DeliveryRepository deliveries) {
        this.registry = registry;
        this.deliveries = deliveries;
        Gauge.builder(DELIVERIES_PENDING, snapshot, s -> s.get().pending())
                .description("Webhook deliveries waiting to be processed")
                .register(registry);
        Gauge.builder(DELIVERIES_OLDEST_PENDING_AGE, snapshot, s -> s.get().oldestPendingAgeSeconds())
                .description("Age of the oldest webhook delivery waiting to be processed")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder(DELIVERIES_PARKED, snapshot, s -> s.get().parked())
                .description("Webhook deliveries that failed every attempt and need an operator")
                .register(registry);
        for (String kind : List.of(KIND_MALFORMED, KIND_RATE_LIMITED, KIND_GITHUB_UNAVAILABLE, KIND_ERROR)) {
            registry.counter(DELIVERIES_FAILURES, TAG_KIND, kind);
        }
    }

    @Scheduled(fixedDelayString = "${pallet.git.delivery.metrics-interval:PT15S}")
    public void refresh() {
        try {
            DeliveryStats stats = deliveries.stats();
            snapshot.set(new Snapshot(
                    stats.getPending().doubleValue(),
                    stats.getOldestPendingAgeSeconds().doubleValue(),
                    stats.getParked().doubleValue()));
        } catch (DataAccessException e) {
            log.warn(
                    "Could not refresh the delivery gauges, keeping the last values: {}",
                    e.getClass().getName());
        }
    }

    void completed(String event) {
        registry.counter(DELIVERIES_PROCESSED, TAG_EVENT, SubscribedEvents.metricLabel(event))
                .increment();
    }

    void failed(String kind) {
        registry.counter(DELIVERIES_FAILURES, TAG_KIND, kind).increment();
    }

    private record Snapshot(double pending, double oldestPendingAgeSeconds, double parked) {}
}
