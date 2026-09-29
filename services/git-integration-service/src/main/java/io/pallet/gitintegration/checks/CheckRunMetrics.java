package io.pallet.gitintegration.checks;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.gitintegration.projection.ProjectionMetrics;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Every tag value is one of the enums below; never an org, app, installation, or commit. */
@Component
public class CheckRunMetrics {

    public static final String DESIRED = "git.checks.desired";
    public static final String DROPPED = "git.checks.dropped";
    public static final String REPORTED = "git.checks.reported";
    public static final String FAILURES = "git.checks.failures";

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
    }

    void desired(boolean applied) {
        registry.counter(DESIRED, "result", applied ? "applied" : "unchanged").increment();
    }

    void dropped(DropReason reason) {
        registry.counter(DROPPED, "reason", reason.tag()).increment();
    }

    void reported(Reported outcome) {
        registry.counter(REPORTED, "outcome", outcome.tag()).increment();
    }

    void failed(FailureKind kind) {
        registry.counter(FAILURES, "kind", kind.tag()).increment();
    }

    void countingFailures(String listener, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException failure) {
            registry.counter(ProjectionMetrics.EVENTS_FAILED, ProjectionMetrics.TAG_LISTENER, listener)
                    .increment();
            throw failure;
        }
    }
}
