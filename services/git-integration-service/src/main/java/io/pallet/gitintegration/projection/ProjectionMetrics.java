package io.pallet.gitintegration.projection;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class ProjectionMetrics {

    public static final String PROJECTION_LAG = "git.authz.projection_lag_seconds";
    public static final String EVENTS_FAILED = "git.events.failed";
    public static final String TAG_LISTENER = "listener";

    private final MeterRegistry registry;
    private final Clock clock;
    private final Timer projectionLag;

    ProjectionMetrics(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
        this.projectionLag = Timer.builder(PROJECTION_LAG)
                .description("Time from a membership change in org-team-service to it reaching the read model")
                .publishPercentileHistogram()
                .register(registry);
    }

    void membershipApplied(Instant occurredAt) {
        Duration lag = Duration.between(occurredAt, clock.instant());
        projectionLag.record(lag.isNegative() ? Duration.ZERO : lag);
    }

    void countingFailures(String listener, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException failure) {
            registry.counter(EVENTS_FAILED, TAG_LISTENER, listener).increment();
            throw failure;
        }
    }
}
