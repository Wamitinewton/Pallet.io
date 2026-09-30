package io.pallet.gitintegration.retention;

import static io.pallet.gitintegration.observability.MetricsCatalog.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.pallet.gitintegration.retention.RetentionSweeps.Sweep;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Rows each sweep removed or nulled, tagged by table, and how long each run took, tagged by sweep. */
@Component
class RetentionMetrics {

    private final MeterRegistry registry;
    private final Map<Sweep, Counter> rows = new EnumMap<>(Sweep.class);
    private final Map<Sweep, Timer> durations = new EnumMap<>(Sweep.class);

    RetentionMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (Sweep sweep : Sweep.values()) {
            rows.put(
                    sweep,
                    Counter.builder(sweep.nulls() ? RETENTION_NULLED : RETENTION_DELETED)
                            .description(sweep.nulls() ? "Rows retention nulled" : "Rows retention deleted")
                            .tag(TAG_TABLE, sweep.table())
                            .register(registry));
            durations.put(
                    sweep,
                    Timer.builder(RETENTION_RUN_DURATION)
                            .description("Duration of one retention sweep run")
                            .tag(TAG_SWEEP, sweep.tag())
                            .register(registry));
        }
    }

    Timer.Sample start() {
        return Timer.start(registry);
    }

    void rows(Sweep sweep, int count) {
        rows.get(sweep).increment(count);
    }

    void ran(Sweep sweep, Timer.Sample sample) {
        sample.stop(durations.get(sweep));
    }
}
