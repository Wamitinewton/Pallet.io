package io.pallet.gitintegration.checks;

import static io.pallet.gitintegration.observability.MetricsCatalog.*;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Every tag value is one of the enums below; never an org, app, installation, or commit. */
@Component
public class CheckRunMetrics {

    /** Why a build or deploy event, or a pending check run, will never reach GitHub. */
    public enum DropReason {
        UNLINKED,
        UNTRACKED,
        DISCONNECTED,
        MAX_ATTEMPTS;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Reported {
        CREATED,
        UPDATED,
        RECOVERED;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum FailureKind {
        RATE_LIMITED,
        GITHUB_UNAVAILABLE,
        ERROR;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;

    CheckRunMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.counter(CHECKS_DESIRED, TAG_RESULT, "applied");
        registry.counter(CHECKS_DESIRED, TAG_RESULT, "unchanged");
        for (DropReason reason : DropReason.values()) {
            registry.counter(CHECKS_DROPPED, TAG_REASON, reason.tag());
        }
        for (Reported outcome : Reported.values()) {
            registry.counter(CHECKS_REPORTED, TAG_OUTCOME, outcome.tag());
        }
        for (FailureKind kind : FailureKind.values()) {
            registry.counter(CHECKS_FAILURES, TAG_KIND, kind.tag());
        }
    }

    void desired(boolean applied) {
        registry.counter(CHECKS_DESIRED, TAG_RESULT, applied ? "applied" : "unchanged")
                .increment();
    }

    void dropped(DropReason reason) {
        registry.counter(CHECKS_DROPPED, TAG_REASON, reason.tag()).increment();
    }

    void reported(Reported outcome) {
        registry.counter(CHECKS_REPORTED, TAG_OUTCOME, outcome.tag()).increment();
    }

    void failed(FailureKind kind) {
        registry.counter(CHECKS_FAILURES, TAG_KIND, kind.tag()).increment();
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
