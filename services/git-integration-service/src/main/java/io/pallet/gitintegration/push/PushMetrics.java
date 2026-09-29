package io.pallet.gitintegration.push;

import io.micrometer.core.instrument.MeterRegistry;
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

    public static final String CHAIN_RULE_OUTCOME = "git.chain_rule.outcome";
    public static final String PUBLISHED = "git.pushes.published";
    public static final String SKIPPED = "git.pushes.skipped";

    private final MeterRegistry registry;

    PushMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void chainRule(ChainDecision decision) {
        afterCommit(CHAIN_RULE_OUTCOME, "outcome", decision.name().toLowerCase(Locale.ROOT));
    }

    void published(String trigger) {
        afterCommit(PUBLISHED, "trigger", trigger);
    }

    void skipped(String reason) {
        afterCommit(SKIPPED, "reason", reason);
    }

    private void afterCommit(String name, String tag, String value) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            registry.counter(name, tag, value).increment();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                registry.counter(name, tag, value).increment();
            }
        });
    }
}
