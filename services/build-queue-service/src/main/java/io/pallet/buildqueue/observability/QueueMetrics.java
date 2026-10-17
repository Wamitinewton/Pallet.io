package io.pallet.buildqueue.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.pallet.common.events.BuildCancelled;
import io.pallet.common.events.GitPushReceived;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Every meter this service registers (ARCHITECTURE.md §Observability and operations), each registered once at startup
 * with every label value it can carry, so a series exists at zero before its first event and no label value comes from
 * anywhere but the closed sets here. A count recorded inside a transaction is applied when it commits: a rolled-back
 * transition is never counted.
 */
@Component
public class QueueMetrics {

    public static final String PREFIX = "build_queue";

    public static final String REQUESTS = "build_queue.requests";
    public static final String REQUESTS_DROPPED = "build_queue.requests.dropped";
    public static final String DEPTH = "build_queue.depth";
    public static final String IN_FLIGHT = "build_queue.in_flight";
    public static final String CAPACITY = "build_queue.capacity";
    public static final String OLDEST_QUEUED_AGE = "build_queue.oldest_queued_age";
    public static final String OLDEST_DISPATCHABLE_AGE = "build_queue.oldest_dispatchable_age";
    public static final String WAIT = "build_queue.wait";
    public static final String START_LATENCY = "build_queue.start_latency";
    public static final String DISPATCHED = "build_queue.dispatched";
    public static final String DISPATCH_ROUND = "build_queue.dispatch.round";
    public static final String DISPATCH_PAUSED = "build_queue.dispatch.paused";
    public static final String DISPATCH_LISTEN_CONNECTED = "build_queue.dispatch.listen_connected";
    public static final String FINISHED = "build_queue.finished";
    public static final String EVENTS_ANOMALY = "build_queue.events.anomaly";
    public static final String LEASE_SWEEP_SKIPPED = "build_queue.lease.sweep_skipped";
    public static final String RETENTION_DELETED = "build_queue.retention.deleted";
    public static final String AUTHZ_PROJECTION_LAG = "build_queue.authz.projection_lag_seconds";

    public static final String TAG_OUTCOME = "outcome";
    public static final String TAG_TRIGGER = "trigger";
    public static final String TAG_REASON = "reason";
    public static final String TAG_PRIORITY_CLASS = "priority_class";
    public static final String TAG_STATUS = "status";
    public static final String TAG_ATTEMPT = "attempt";
    public static final String TAG_RESULT = "result";
    public static final String TAG_KIND = "kind";
    public static final String TAG_TABLE = "table";

    /** {@code finished} reasons for a result from build-service, whose own failure reason is free text. */
    public static final String REASON_NONE = "NONE";

    public static final String REASON_LATE_RESULT = "LATE_RESULT";

    public static final Set<String> FORBIDDEN_TAG_KEYS = Set.of(
            "org",
            "org_id",
            "orgId",
            "app",
            "app_id",
            "appId",
            "build_id",
            "buildId",
            "user_id",
            "userId",
            "repo",
            "commit",
            "commit_sha",
            "push_event_id");

    public static final List<String> TRIGGERS = List.of(
            GitPushReceived.TRIGGER_WEBHOOK,
            GitPushReceived.TRIGGER_MANUAL,
            GitPushReceived.TRIGGER_LINKED,
            GitPushReceived.TRIGGER_RECONCILED);

    /** Wait SLO: push to dispatch p95 under 1 s when nothing but the next round held the build. */
    static final List<Duration> WAIT_BUCKETS = List.of(
            Duration.ofMillis(250),
            Duration.ofSeconds(1),
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofMinutes(5),
            Duration.ofMinutes(30));

    static final List<Duration> START_LATENCY_BUCKETS = List.of(
            Duration.ofSeconds(1),
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofMinutes(1),
            Duration.ofMinutes(5));

    private static final String ATTEMPT_FIRST = "1";
    private static final String ATTEMPT_SECOND = "2";
    private static final String ATTEMPT_LATER = "3+";

    public enum RequestOutcome {
        QUEUED,
        REJECTED
    }

    public enum DropReason {
        APP_DELETED,
        ORG_DELETED
    }

    /** {@code builds.priority_class}: the rank is the stored value. */
    public enum Priority {
        INTERACTIVE(0),
        AUTOMATIC(1);

        private final int rank;

        Priority(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }
    }

    public enum InFlight {
        DISPATCHED,
        RUNNING
    }

    public enum RoundResult {
        DISPATCHED,
        IDLE,
        LOCK_BUSY,
        PAUSED,
        ERROR
    }

    /** A terminal transition and the reasons it can carry. */
    public enum Outcome {
        SUCCEEDED(REASON_NONE, REASON_LATE_RESULT),
        FAILED(REASON_NONE, REASON_LATE_RESULT),
        SUPERSEDED(BuildCancelled.REASON_SUPERSEDED),
        CANCELLED(
                BuildCancelled.REASON_USER_CANCELLED,
                BuildCancelled.REASON_APP_DELETED,
                BuildCancelled.REASON_ORG_DELETED),
        REJECTED(BuildCancelled.REASON_QUEUE_FULL),
        EXPIRED(
                BuildCancelled.REASON_QUEUE_TIMEOUT,
                BuildCancelled.REASON_START_TIMEOUT,
                BuildCancelled.REASON_LEASE_EXPIRED);

        private final List<String> reasons;

        Outcome(String... reasons) {
            this.reasons = List.of(reasons);
        }

        public List<String> reasons() {
            return reasons;
        }
    }

    public enum Anomaly {
        UNTRACKED,
        OUT_OF_ORDER,
        LATE_START,
        LATE_RESULT,
        IMPOSSIBLE
    }

    public enum RetentionTable {
        BUILDS,
        APPS,
        ORGS,
        ORG_MEMBERSHIPS,
        OUTBOX_EVENTS,
        PROCESSED_EVENTS
    }

    private final MeterRegistry registry;
    private final Clock clock;
    private final Map<RequestOutcome, Map<String, Counter>> requests = new EnumMap<>(RequestOutcome.class);
    private final Map<DropReason, Counter> dropped;
    private final Map<Priority, Timer> waits;
    private final Timer startLatency;
    private final Map<String, Counter> dispatched;
    private final Map<Outcome, Map<String, Counter>> finished = new EnumMap<>(Outcome.class);
    private final Map<Anomaly, Counter> anomalies;
    private final Counter sweepSkipped;
    private final Map<RetentionTable, Counter> retentionDeleted;
    private final Timer projectionLag;
    private final AtomicInteger listenConnected = new AtomicInteger();

    QueueMetrics(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
        for (RequestOutcome outcome : RequestOutcome.values()) {
            requests.put(
                    outcome,
                    TRIGGERS.stream()
                            .collect(Collectors.toUnmodifiableMap(
                                    Function.identity(),
                                    trigger -> Counter.builder(REQUESTS)
                                            .description("Build requests intake accepted, by what became of them")
                                            .tag(TAG_OUTCOME, tag(outcome))
                                            .tag(TAG_TRIGGER, trigger)
                                            .register(registry))));
        }
        dropped = byEnum(
                DropReason.class,
                reason -> Counter.builder(REQUESTS_DROPPED)
                        .description("Build requests dropped against a deleted app or org")
                        .tag(TAG_REASON, tag(reason))
                        .register(registry));
        waits = byEnum(
                Priority.class,
                priority -> Timer.builder(WAIT)
                        .description("Enqueue to first dispatch, from the build's stored timestamps")
                        .tag(TAG_PRIORITY_CLASS, tag(priority))
                        .serviceLevelObjectives(WAIT_BUCKETS.toArray(Duration[]::new))
                        .register(registry));
        startLatency = Timer.builder(START_LATENCY)
                .description("First dispatch to build.started, from the build's stored timestamps")
                .serviceLevelObjectives(START_LATENCY_BUCKETS.toArray(Duration[]::new))
                .register(registry);
        dispatched = List.of(ATTEMPT_FIRST, ATTEMPT_SECOND, ATTEMPT_LATER).stream()
                .collect(Collectors.toUnmodifiableMap(
                        Function.identity(),
                        attempt -> Counter.builder(DISPATCHED)
                                .description("Dispatches written to the outbox; an attempt above 1 is a redispatch")
                                .tag(TAG_ATTEMPT, attempt)
                                .register(registry)));
        for (Outcome outcome : Outcome.values()) {
            finished.put(
                    outcome,
                    outcome.reasons().stream()
                            .collect(Collectors.toUnmodifiableMap(
                                    Function.identity(),
                                    reason -> Counter.builder(FINISHED)
                                            .description("Builds reaching a final status, or expiring")
                                            .tag(TAG_OUTCOME, tag(outcome))
                                            .tag(TAG_REASON, reason)
                                            .register(registry))));
        }
        anomalies = byEnum(
                Anomaly.class,
                kind -> Counter.builder(EVENTS_ANOMALY)
                        .description("build-service reports that matched no ordinary transition")
                        .tag(TAG_KIND, tag(kind))
                        .register(registry));
        sweepSkipped = Counter.builder(LEASE_SWEEP_SKIPPED)
                .description("Lease sweeps that held start and run deadlines because the backbone was unreachable")
                .register(registry);
        retentionDeleted = byEnum(
                RetentionTable.class,
                table -> Counter.builder(RETENTION_DELETED)
                        .description("Rows retention deleted")
                        .tag(TAG_TABLE, tag(table))
                        .register(registry));
        projectionLag = Timer.builder(AUTHZ_PROJECTION_LAG)
                .description("Time from a membership change in org-team-service to it reaching the read model")
                .publishPercentileHistogram()
                .register(registry);
        Gauge.builder(DISPATCH_LISTEN_CONNECTED, listenConnected, AtomicInteger::get)
                .description("1 while this instance holds its dispatch LISTEN connection")
                .register(registry);
        for (RoundResult result : RoundResult.values()) {
            roundTimer(result);
        }
    }

    /** {@code trigger} has been validated against {@link #TRIGGERS}; anything else is a bug and fails the caller. */
    public void requested(RequestOutcome outcome, String trigger) {
        Counter counter = requests.get(outcome).get(trigger);
        if (counter == null) {
            throw new IllegalArgumentException("Unknown trigger " + trigger);
        }
        AfterCommit.run(counter::increment);
    }

    public void dropped(DropReason reason) {
        AfterCommit.run(dropped.get(reason)::increment);
    }

    /** A dispatch of {@code attempt}; the first one also records how long the build waited for its slot. */
    public void dispatched(int attempt, Priority priority, Instant enqueuedAt, Instant dispatchedAt) {
        Counter counter = dispatched.get(attemptTag(attempt));
        if (attempt != 1) {
            AfterCommit.run(counter::increment);
            return;
        }
        Duration wait = nonNegative(Duration.between(enqueuedAt, dispatchedAt));
        Timer timer = waits.get(priority);
        AfterCommit.run(() -> {
            counter.increment();
            timer.record(wait);
        });
    }

    /** Skipped for a build moved to {@code RUNNING} by hand, which has no recorded dispatch to measure from. */
    public void started(Instant firstDispatchedAt, Instant startedAt) {
        if (firstDispatchedAt == null || startedAt == null) {
            return;
        }
        Duration latency = nonNegative(Duration.between(firstDispatchedAt, startedAt));
        AfterCommit.run(() -> startLatency.record(latency));
    }

    /** @throws IllegalArgumentException if {@code reason} is not one {@code outcome} can carry */
    public void finished(Outcome outcome, String reason) {
        Counter counter = finished.get(outcome).get(reason);
        if (counter == null) {
            throw new IllegalArgumentException("Outcome " + outcome + " has no reason " + reason);
        }
        AfterCommit.run(counter::increment);
    }

    public void anomaly(Anomaly kind) {
        AfterCommit.run(anomalies.get(kind)::increment);
    }

    public void sweepSkipped() {
        AfterCommit.run(sweepSkipped::increment);
    }

    public void retentionDeleted(RetentionTable table, long rows) {
        if (rows > 0) {
            AfterCommit.run(() -> retentionDeleted.get(table).increment(rows));
        }
    }

    public void membershipApplied(Instant occurredAt) {
        Duration lag = nonNegative(Duration.between(occurredAt, clock.instant()));
        AfterCommit.run(() -> projectionLag.record(lag));
    }

    public void listenConnected(boolean connected) {
        listenConnected.set(connected ? 1 : 0);
    }

    public Timer.Sample startRound() {
        return Timer.start(registry);
    }

    /** Called once the round's transaction has ended, so it is never deferred. */
    public void roundEnded(Timer.Sample sample, RoundResult result) {
        sample.stop(roundTimer(result));
    }

    public static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    static String attemptTag(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("Attempts start at 1, got " + attempt);
        }
        return switch (attempt) {
            case 1 -> ATTEMPT_FIRST;
            case 2 -> ATTEMPT_SECOND;
            default -> ATTEMPT_LATER;
        };
    }

    private Timer roundTimer(RoundResult result) {
        return Timer.builder(DISPATCH_ROUND)
                .description("Dispatcher round duration, by what the round did")
                .tag(TAG_RESULT, tag(result))
                .register(registry);
    }

    private static <E extends Enum<E>, M> Map<E, M> byEnum(Class<E> type, Function<E, M> meter) {
        Map<E, M> meters = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) {
            meters.put(value, meter.apply(value));
        }
        return meters;
    }

    private static Duration nonNegative(Duration duration) {
        return duration.isNegative() ? Duration.ZERO : duration;
    }
}
