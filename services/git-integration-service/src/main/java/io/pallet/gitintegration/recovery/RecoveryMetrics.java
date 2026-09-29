package io.pallet.gitintegration.recovery;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * The recovery jobs' meters. A sustained non-zero {@code git.reconciler.pushes_found} means the backstop is doing the
 * webhook path's job. Every tag value is a fixed code.
 */
@Component
public class RecoveryMetrics {

    public static final String REDELIVERY_REQUESTED = "git.redelivery.requested";
    public static final String REDELIVERY_FAILED = "git.redelivery.failed";
    public static final String RECONCILER_CHECKED = "git.reconciler.checked";
    public static final String RECONCILER_PUSHES_FOUND = "git.reconciler.pushes_found";
    public static final String RECONCILER_RUN_DURATION = "git.reconciler.run_duration";

    /** What one fetch of a repository branch found, across every link that shares it. */
    public enum Checked {
        NOT_MODIFIED,
        UNCHANGED,
        CHANGED;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;
    private final Counter requested;
    private final Counter failed;
    private final Counter pushesFound;
    private final Timer runDuration;

    RecoveryMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.requested = Counter.builder(REDELIVERY_REQUESTED)
                .description("Redeliveries of failed webhook deliveries asked of GitHub")
                .register(registry);
        this.failed = Counter.builder(REDELIVERY_FAILED)
                .description("Redelivery requests GitHub didn't accept")
                .register(registry);
        this.pushesFound = Counter.builder(RECONCILER_PUSHES_FOUND)
                .description("Pushes the head reconciler published because no webhook had")
                .register(registry);
        this.runDuration = Timer.builder(RECONCILER_RUN_DURATION)
                .description("Duration of one head reconciler run")
                .register(registry);
        for (Checked result : Checked.values()) {
            checkedCounter(result);
        }
    }

    void redeliveryRequested() {
        requested.increment();
    }

    void redeliveryFailed() {
        failed.increment();
    }

    void checked(Checked result) {
        checkedCounter(result).increment();
    }

    void pushFound() {
        pushesFound.increment();
    }

    void reconcilerRan(Duration duration) {
        runDuration.record(duration);
    }

    private Counter checkedCounter(Checked result) {
        return Counter.builder(RECONCILER_CHECKED)
                .description("Repository branches the head reconciler read from GitHub, by what it found")
                .tag("result", result.tag())
                .register(registry);
    }
}
