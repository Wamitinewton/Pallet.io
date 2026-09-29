package io.pallet.gitintegration.access;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.pallet.gitintegration.repolink.RepoLinkRepository;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Access re-verification's meters. {@code git.reverify.oldest_check_age_seconds} is refreshed on its own schedule, not
 * by the job, so it keeps growing when the job stops running; past 48 hours it pages.
 */
@Component
public class ReverifyMetrics {

    public static final String CHECKED = "git.reverify.checked";
    public static final String DISCONNECTED = "git.reverify.disconnected";
    public static final String OLDEST_CHECK_AGE = "git.reverify.oldest_check_age_seconds";
    public static final String RUN_DURATION = "git.reverify.run_duration";

    private static final Logger log = LoggerFactory.getLogger(ReverifyMetrics.class);

    private final MeterRegistry registry;
    private final RepoLinkRepository links;
    private final Counter disconnected;
    private final Timer runDuration;
    private final AtomicReference<Double> oldestCheckAge = new AtomicReference<>(0.0);

    ReverifyMetrics(MeterRegistry registry, RepoLinkRepository links) {
        this.registry = registry;
        this.links = links;
        this.disconnected = Counter.builder(DISCONNECTED)
                .description("Repo links disconnected because their verifier lost access on GitHub")
                .register(registry);
        this.runDuration = Timer.builder(RUN_DURATION)
                .description("Duration of one access re-verification run")
                .register(registry);
        Gauge.builder(OLDEST_CHECK_AGE, oldestCheckAge, AtomicReference::get)
                .description("Time since the least recently re-verified active link was checked against GitHub")
                .baseUnit("seconds")
                .register(registry);
        for (ReverifyOutcome.Kind kind : ReverifyOutcome.Kind.values()) {
            checkedCounter(kind);
        }
    }

    @Scheduled(fixedDelayString = "${pallet.git.reverify.metrics-interval:PT1M}")
    public void refresh() {
        try {
            oldestCheckAge.set(links.oldestAccessCheckAgeSeconds());
        } catch (DataAccessException e) {
            log.warn(
                    "Could not refresh the re-verification gauge, keeping the last value: {}",
                    e.getClass().getName());
        }
    }

    void checked(ReverifyOutcome.Kind kind) {
        checkedCounter(kind).increment();
    }

    void disconnected() {
        disconnected.increment();
    }

    void ran(Duration duration) {
        runDuration.record(duration);
    }

    private Counter checkedCounter(ReverifyOutcome.Kind kind) {
        return Counter.builder(CHECKED)
                .description("Verifier permissions read from GitHub, by what GitHub's answer meant")
                .tag("outcome", kind.tag())
                .register(registry);
    }
}
