package io.pallet.gitintegration.push;

import static io.pallet.gitintegration.observability.MetricsCatalog.*;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.pallet.common.events.GitPushReceived;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Push outcomes, counted once the delivery's transaction commits: a round that rolls back, or is run again after a
 * GitHub lookup, counts nothing. Every tag value is a fixed code.
 */
@Component
public class PushMetrics {

    private final MeterRegistry registry;
    private final Clock clock;
    private final Timer toOutbox;

    PushMetrics(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
        this.toOutbox = Timer.builder(PUSH_TO_OUTBOX_LATENCY)
                .description("Time from a push webhook being stored to its event being appended to the outbox")
                .publishPercentileHistogram()
                .serviceLevelObjectives(PUSH_TO_OUTBOX_SLO.toArray(Duration[]::new))
                .register(registry);
        for (String trigger : List.of(
                GitPushReceived.TRIGGER_WEBHOOK,
                GitPushReceived.TRIGGER_MANUAL,
                GitPushReceived.TRIGGER_LINKED,
                GitPushReceived.TRIGGER_RECONCILED)) {
            registry.counter(PUSHES_PUBLISHED, TAG_TRIGGER, trigger);
        }
    }

    void chainRule(ChainDecision decision) {
        afterCommit(() -> registry.counter(
                        CHAIN_RULE_OUTCOME, TAG_OUTCOME, decision.name().toLowerCase(Locale.ROOT))
                .increment());
    }

    /** @param receivedAt when the delivery was stored; the latency is measured to now, the moment of the append */
    void published(String trigger, Instant receivedAt) {
        Duration latency = Duration.between(receivedAt, clock.instant());
        afterCommit(() -> toOutbox.record(latency.isNegative() ? Duration.ZERO : latency));
        published(trigger);
    }

    public void published(String trigger) {
        afterCommit(
                () -> registry.counter(PUSHES_PUBLISHED, TAG_TRIGGER, trigger).increment());
    }

    void skipped(String reason) {
        afterCommit(() -> registry.counter(PUSHES_SKIPPED, TAG_REASON, reason).increment());
    }

    private static void afterCommit(Runnable count) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            count.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                count.run();
            }
        });
    }
}
