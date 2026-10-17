package io.pallet.buildqueue.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import io.pallet.buildqueue.config.BuildQueueProperties;
import io.pallet.buildqueue.config.BuildQueueProperties.Limits;
import io.pallet.buildqueue.config.CrossTenant;
import io.pallet.buildqueue.observability.QueueMetrics.InFlight;
import io.pallet.buildqueue.observability.QueueMetrics.Priority;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * The queue's platform-wide gauges, read from the database every {@code observability.gauge-interval} in one
 * statement over the partial indexes of waiting and in-flight builds. Every instance reports the same numbers, so a
 * dashboard takes the {@code max} across instances, never the sum. Ages are measured against database {@code now()}.
 *
 * <p>{@code oldest_dispatchable_age} is the oldest waiting build that neither its app's in-flight build nor its org's
 * {@code maxConcurrent} is holding: only platform capacity, a pause or the dispatcher itself can keep it waiting.
 */
@Component
public class QueueGauges implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(QueueGauges.class);

    private static final String ACTIVE_BUILDS = """
            WITH in_flight AS (
                     SELECT org_id, app_id, status
                       FROM build_queue.builds
                      WHERE status IN ('DISPATCHED', 'RUNNING')),
                 queued AS (
                     SELECT b.org_id, b.priority_class, b.enqueued_at,
                            NOT EXISTS (SELECT 1 FROM in_flight f WHERE f.app_id = b.app_id) AS app_free
                       FROM build_queue.builds b
                      WHERE b.status = 'QUEUED')
            SELECT o.org_id,
                   coalesce(q.interactive, 0) AS interactive,
                   coalesce(q.automatic, 0) AS automatic,
                   coalesce(f.dispatched, 0) AS dispatched,
                   coalesce(f.running, 0) AS running,
                   q.oldest_enqueued_at,
                   q.oldest_app_free_enqueued_at,
                   now() AS observed_at
              FROM (SELECT org_id FROM queued UNION SELECT org_id FROM in_flight) o
              LEFT JOIN (SELECT org_id,
                                count(*) FILTER (WHERE priority_class = :interactive) AS interactive,
                                count(*) FILTER (WHERE priority_class = :automatic) AS automatic,
                                min(enqueued_at) AS oldest_enqueued_at,
                                min(enqueued_at) FILTER (WHERE app_free) AS oldest_app_free_enqueued_at
                           FROM queued
                          GROUP BY org_id) q ON q.org_id = o.org_id
              LEFT JOIN (SELECT org_id,
                                count(*) FILTER (WHERE status = 'DISPATCHED') AS dispatched,
                                count(*) FILTER (WHERE status = 'RUNNING') AS running
                           FROM in_flight
                          GROUP BY org_id) f ON f.org_id = o.org_id
            """;

    /** What one org has waiting and in flight; the org id never leaves this class. */
    record OrgActivity(
            String orgId,
            long interactive,
            long automatic,
            long dispatched,
            long running,
            Instant oldestEnqueuedAt,
            Instant oldestAppFreeEnqueuedAt,
            Instant observedAt) {

        long inFlight() {
            return dispatched + running;
        }
    }

    record Snapshot(
            long interactive,
            long automatic,
            long dispatched,
            long running,
            double oldestQueuedAgeSeconds,
            double oldestDispatchableAgeSeconds) {

        static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0, 0, 0);

        long depth(Priority priority) {
            return priority == Priority.INTERACTIVE ? interactive : automatic;
        }

        long inFlight(InFlight status) {
            return status == InFlight.DISPATCHED ? dispatched : running;
        }
    }

    private final JdbcClient jdbc;
    private final BuildQueueProperties properties;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.EMPTY);

    QueueGauges(JdbcClient jdbc, BuildQueueProperties properties, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.properties = properties;
        for (Priority priority : Priority.values()) {
            gauge(QueueMetrics.DEPTH, "Waiting builds, platform-wide", s -> s.depth(priority))
                    .tag(QueueMetrics.TAG_PRIORITY_CLASS, QueueMetrics.tag(priority))
                    .register(registry);
        }
        for (InFlight status : InFlight.values()) {
            gauge(QueueMetrics.IN_FLIGHT, "Builds holding a slot, platform-wide", s -> s.inFlight(status))
                    .tag(QueueMetrics.TAG_STATUS, QueueMetrics.tag(status))
                    .register(registry);
        }
        TimeGauge.builder(
                        QueueMetrics.OLDEST_QUEUED_AGE,
                        snapshot,
                        TimeUnit.SECONDS,
                        current -> current.get().oldestQueuedAgeSeconds())
                .description("How long the oldest waiting build has waited")
                .register(registry);
        TimeGauge.builder(
                        QueueMetrics.OLDEST_DISPATCHABLE_AGE,
                        snapshot,
                        TimeUnit.SECONDS,
                        current -> current.get().oldestDispatchableAgeSeconds())
                .description("How long the oldest build no app or org limit is holding has waited")
                .register(registry);
        Gauge.builder(QueueMetrics.CAPACITY, properties, p -> p.dispatch().globalConcurrency())
                .description("dispatch.global-concurrency: builds build-service can run at once")
                .register(registry);
        Gauge.builder(
                        QueueMetrics.DISPATCH_PAUSED,
                        properties,
                        p -> p.dispatch().paused() ? 1 : 0)
                .description("1 while dispatch.paused holds every waiting build")
                .register(registry);
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::refresh, properties.observability().gaugeInterval());
    }

    /** Keeps the last values when the database can't be read: a gauge that drops to zero would hide the outage. */
    public void refresh() {
        try {
            snapshot.set(summarize(activeBuilds(), properties.limits()));
        } catch (DataAccessException e) {
            log.warn("Queue gauges not refreshed: {}", e.getClass().getSimpleName());
        }
    }

    @CrossTenant("platform-wide gauges count every org's builds; no org id leaves this class")
    List<OrgActivity> activeBuilds() {
        return jdbc.sql(ACTIVE_BUILDS)
                .param("interactive", Priority.INTERACTIVE.rank())
                .param("automatic", Priority.AUTOMATIC.rank())
                .query((row, rowNumber) -> new OrgActivity(
                        row.getString("org_id"),
                        row.getLong("interactive"),
                        row.getLong("automatic"),
                        row.getLong("dispatched"),
                        row.getLong("running"),
                        instant(row.getObject("oldest_enqueued_at", OffsetDateTime.class)),
                        instant(row.getObject("oldest_app_free_enqueued_at", OffsetDateTime.class)),
                        instant(row.getObject("observed_at", OffsetDateTime.class))))
                .list();
    }

    /** Folds every org's activity into platform-wide numbers; an org at its {@code maxConcurrent} has nothing dispatchable. */
    static Snapshot summarize(List<OrgActivity> orgs, Limits limits) {
        long interactive = 0;
        long automatic = 0;
        long dispatched = 0;
        long running = 0;
        Duration oldestQueued = Duration.ZERO;
        Duration oldestDispatchable = Duration.ZERO;
        for (OrgActivity org : orgs) {
            interactive += org.interactive();
            automatic += org.automatic();
            dispatched += org.dispatched();
            running += org.running();
            oldestQueued = longer(oldestQueued, age(org.oldestEnqueuedAt(), org.observedAt()));
            if (org.inFlight() < limits.forOrg(org.orgId()).maxConcurrent()) {
                oldestDispatchable = longer(oldestDispatchable, age(org.oldestAppFreeEnqueuedAt(), org.observedAt()));
            }
        }
        return new Snapshot(
                interactive, automatic, dispatched, running, seconds(oldestQueued), seconds(oldestDispatchable));
    }

    private Gauge.Builder<AtomicReference<Snapshot>> gauge(
            String name, String description, ToDoubleFunction<Snapshot> value) {
        return Gauge.builder(name, snapshot, current -> value.applyAsDouble(current.get()))
                .description(description);
    }

    private static Duration age(Instant since, Instant now) {
        return since == null || since.isAfter(now) ? Duration.ZERO : Duration.between(since, now);
    }

    private static Duration longer(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static double seconds(Duration duration) {
        return duration.toNanos() / 1e9;
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
