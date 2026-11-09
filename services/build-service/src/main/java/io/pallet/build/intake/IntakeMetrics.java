package io.pallet.build.intake;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.build.observability.AfterCommit;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** What intake did with each dispatch it applied. A redelivery the inbox stopped is not a dispatch here. */
@Component
public class IntakeMetrics {

    public static final String DISPATCHES = "build.dispatches";
    public static final String TAG_OUTCOME = "outcome";

    public enum Outcome {
        /** A new build that can start in time. */
        ACCEPTED,
        /** A later attempt of a build that hadn't started, now waiting again. */
        REACCEPTED,
        /** A dispatch for a build already recorded that changed nothing. */
        DUPLICATE,
        /** A new build or a later attempt that arrived too close to its start deadline to be started. */
        LATE,
        /** A dispatch for a deleted app or org, failed at once. */
        TORN_DOWN;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);

    IntakeMetrics(MeterRegistry registry) {
        for (Outcome outcome : Outcome.values()) {
            counters.put(
                    outcome,
                    Counter.builder(DISPATCHES)
                            .description("Dispatches applied by intake, by what became of them")
                            .tag(TAG_OUTCOME, outcome.tag())
                            .register(registry));
        }
    }

    /** Counted once the caller's transaction commits, so a retried delivery is counted once. */
    void dispatched(Outcome outcome) {
        Counter counter = counters.get(outcome);
        AfterCommit.run(counter::increment);
    }
}
