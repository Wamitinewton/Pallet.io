package io.pallet.build.projection;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** How far the read models trail the services that own their facts. */
@Component
public class ProjectionMetrics {

    public static final String AUTHZ_PROJECTION_LAG = "build.authz.projection_lag_seconds";

    private final Clock clock;
    private final Timer projectionLag;

    ProjectionMetrics(MeterRegistry registry, Clock clock) {
        this.clock = clock;
        this.projectionLag = Timer.builder(AUTHZ_PROJECTION_LAG)
                .description("Time from a membership change in org-team-service to it reaching the read model")
                .publishPercentileHistogram()
                .register(registry);
    }

    /** Recorded once the caller's transaction commits, so a rolled-back record is never counted. */
    void membershipApplied(Instant occurredAt) {
        Duration lag = Duration.between(occurredAt, clock.instant());
        Duration recorded = lag.isNegative() ? Duration.ZERO : lag;
        afterCommit(() -> projectionLag.record(recorded));
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
