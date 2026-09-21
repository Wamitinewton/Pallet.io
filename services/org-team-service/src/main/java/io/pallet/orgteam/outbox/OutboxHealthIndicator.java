package io.pallet.orgteam.outbox;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("outbox")
class OutboxHealthIndicator implements HealthIndicator {

    private final OutboxMetrics metrics;

    OutboxHealthIndicator(OutboxMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public Health health() {
        OutboxStats stats = metrics.current();
        return Health.up()
                .withDetail("pending", stats.pending())
                .withDetail("oldestPendingAgeSeconds", Math.round(stats.oldestPendingAgeSeconds()))
                .withDetail("parked", stats.parked())
                .build();
    }
}
