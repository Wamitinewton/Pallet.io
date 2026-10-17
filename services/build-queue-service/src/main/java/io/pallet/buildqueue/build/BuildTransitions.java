package io.pallet.buildqueue.build;

import io.pallet.buildqueue.config.CrossTenant;
import io.pallet.buildqueue.observability.QueueMetrics;
import io.pallet.buildqueue.observability.QueueMetrics.Outcome;
import io.pallet.buildqueue.observability.QueueMetrics.Priority;
import io.pallet.buildqueue.observability.TransitionLog;
import io.pallet.common.events.BuildCancelled;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every write to a build's status. A change is one guarded statement keyed by {@code (build_id, org_id)}, plus
 * {@code app_id} for build-service's reports, that names the statuses it may move from; the caller acts on whether it
 * matched. The lease sweeper's statements are the exception: they span orgs and select by deadline. Timestamps and
 * deadlines come from database {@code now()}.
 *
 * <p>Each change that matched is logged once through {@link TransitionLog} and counted through {@link QueueMetrics},
 * with durations taken from the timestamps the statement returned.
 */
@Component
public class BuildTransitions {

    /** The longest failure reason stored; build-service's is a short cause, never a log. */
    public static final int MAX_FAILURE_REASON_LENGTH = 500;

    private static final String INSERT = """
            INSERT INTO build_queue.builds
                (build_id, org_id, app_id, branch, commit_sha, push_event_id, trigger, priority_class, provider,
                 installation_id, repo_id, repo, root_directory, head_commit_message, head_commit_author, traceparent,
                 status, status_reason, finished_at)
            VALUES
                (:buildId, :orgId, :appId, :branch, :commitSha, :pushEventId, :trigger, :priorityClass, :provider,
                 :installationId, :repoId, :repo, :rootDirectory, :headCommitMessage, :headCommitAuthor, :traceparent,
                 :status, :statusReason, CASE WHEN :finished THEN now() END)
            """;

    private static final String SUPERSEDE = """
            UPDATE build_queue.builds
               SET status = 'SUPERSEDED', status_reason = 'SUPERSEDED', superseded_by = :supersededBy,
                   finished_at = now(), version = version + 1
             WHERE build_id = :buildId AND org_id = :orgId AND status = 'QUEUED'
            RETURNING app_id
            """;

    private static final String CANCEL = """
            UPDATE build_queue.builds
               SET status = 'CANCELLED', status_reason = :reason, cancelled_by = :cancelledBy,
                   finished_at = now(), version = version + 1
             WHERE build_id = :buildId AND org_id = :orgId AND status = 'QUEUED'
            RETURNING app_id
            """;

    /**
     * Cancels every waiting build matching {@code {scope}}. The CTE's row locks re-check {@code QUEUED} against a
     * concurrent writer, so a build the lease sweeper expired first is left out.
     */
    private static final String CANCEL_ALL_QUEUED = """
            WITH waiting AS (
                SELECT build_id FROM build_queue.builds
                 WHERE {scope} AND status = 'QUEUED'
                 ORDER BY enqueued_at
                   FOR UPDATE)
            UPDATE build_queue.builds b
               SET status = 'CANCELLED', status_reason = :reason, finished_at = now(), version = b.version + 1
              FROM waiting
             WHERE b.build_id = waiting.build_id
            RETURNING b.build_id, b.org_id, b.app_id, b.commit_sha, b.push_event_id
            """;

    private static final String CANCEL_ALL_QUEUED_OF_ORG = CANCEL_ALL_QUEUED.replace("{scope}", "org_id = :orgId");

    private static final String CANCEL_ALL_QUEUED_OF_APP =
            CANCEL_ALL_QUEUED.replace("{scope}", "org_id = :orgId AND app_id = :appId");

    private static final String DISPATCH = """
            UPDATE build_queue.builds
               SET status = 'DISPATCHED', attempt = attempt + 1, dispatched_at = now(),
                   start_deadline = now() + :startTimeoutMillis * interval '1 millisecond', version = version + 1
             WHERE build_id = :buildId AND org_id = :orgId AND status = 'QUEUED'
            RETURNING build_id, org_id, app_id, branch, commit_sha, push_event_id, trigger, provider,
                      installation_id, repo_id, repo, root_directory, attempt, start_deadline, priority_class,
                      enqueued_at, dispatched_at, traceparent
            """;

    /**
     * Moves the build from one of {@code {from}} by applying {@code {set}}, returning the status it left and the
     * timestamps it now has. The CTE's row lock re-checks the predicate against a concurrent writer, so the returned
     * status is the one actually replaced.
     */
    private static final String GUARDED_FROM = """
            WITH old AS (
                SELECT build_id, status FROM build_queue.builds
                 WHERE build_id = :buildId AND org_id = :orgId AND app_id = :appId AND status IN ({from})
                   FOR UPDATE)
            UPDATE build_queue.builds b
               SET {set}, version = b.version + 1
              FROM old
             WHERE b.build_id = old.build_id
            RETURNING old.status, b.attempt, b.dispatched_at, b.started_at
            """;

    private static final String RESULT_FROM = "'DISPATCHED', 'RUNNING', 'EXPIRED'";

    private static final String START = guardedFrom("'DISPATCHED'", """
            status = 'RUNNING', started_at = now(),
                   lease_expires_at = now() + :leaseMillis * interval '1 millisecond'""");

    private static final String SUCCEED = guardedFrom(RESULT_FROM, "status = 'SUCCEEDED', finished_at = now()");

    private static final String FAIL = guardedFrom(RESULT_FROM, """
            status = 'FAILED', status_reason = left(CAST(:reason AS text), :maxReasonLength),
                   finished_at = now()""");

    /**
     * Expires up to {@code :limit} builds matching {@code {due}}, oldest deadline first. {@code SKIP LOCKED} passes over
     * a build a listener is deciding right now; the next sweep sees how that ended.
     */
    private static final String EXPIRE = """
            WITH due AS (
                SELECT build_id FROM build_queue.builds
                 WHERE {due}
                 ORDER BY {deadline}
                 LIMIT :limit
                   FOR UPDATE SKIP LOCKED)
            UPDATE build_queue.builds b
               SET status = 'EXPIRED', status_reason = :reason, finished_at = now(), version = b.version + 1
              FROM due
             WHERE b.build_id = due.build_id
            RETURNING b.build_id, b.org_id, b.app_id, b.commit_sha, b.push_event_id, b.attempt, b.traceparent
            """;

    private static final String EXPIRE_QUEUED = expiring(
            "status = 'QUEUED' AND enqueued_at < now() - :maxAgeMillis * interval '1 millisecond'", "enqueued_at");

    private static final String EXPIRE_UNSTARTED =
            expiring("status = 'DISPATCHED' AND start_deadline < now() AND attempt >= :maxAttempts", "start_deadline");

    private static final String EXPIRE_LEASES =
            expiring("status = 'RUNNING' AND lease_expires_at < now()", "lease_expires_at");

    /** {@code dispatched_at} stays the first attempt's: start latency and the queue wait are measured from it. */
    private static final String REDISPATCH = """
            WITH due AS (
                SELECT build_id FROM build_queue.builds
                 WHERE status = 'DISPATCHED' AND start_deadline < now() AND attempt < :maxAttempts
                 ORDER BY start_deadline
                 LIMIT :limit
                   FOR UPDATE SKIP LOCKED)
            UPDATE build_queue.builds b
               SET attempt = b.attempt + 1,
                   start_deadline = now() + :startTimeoutMillis * interval '1 millisecond', version = b.version + 1
              FROM due
             WHERE b.build_id = due.build_id
            RETURNING b.build_id, b.org_id, b.app_id, b.branch, b.commit_sha, b.push_event_id, b.trigger, b.provider,
                      b.installation_id, b.repo_id, b.repo, b.root_directory, b.attempt, b.start_deadline,
                      b.priority_class, b.enqueued_at, b.dispatched_at, b.traceparent
            """;

    private static final String EXTEND_START_DEADLINES = """
            UPDATE build_queue.builds
               SET start_deadline = now() + :graceMillis * interval '1 millisecond', version = version + 1
             WHERE status = 'DISPATCHED' AND start_deadline < now() + :graceMillis * interval '1 millisecond'
            """;

    private static final String EXTEND_LEASES = """
            UPDATE build_queue.builds
               SET lease_expires_at = now() + :graceMillis * interval '1 millisecond', version = version + 1
             WHERE status = 'RUNNING' AND lease_expires_at < now() + :graceMillis * interval '1 millisecond'
            """;

    /** What a guarded move replaced, and the row's timestamps after it. */
    private record Moved(BuildStatus from, int attempt, Instant dispatchedAt, Instant startedAt) {}

    private static final RowMapper<Moved> MOVED = (row, rowNumber) -> new Moved(
            BuildStatus.valueOf(row.getString("status")),
            row.getInt("attempt"),
            instant(row.getObject("dispatched_at", OffsetDateTime.class)),
            instant(row.getObject("started_at", OffsetDateTime.class)));

    private static final RowMapper<DispatchedBuild> DISPATCHED_BUILD = (row, rowNumber) -> new DispatchedBuild(
            row.getObject("build_id", UUID.class),
            row.getString("org_id"),
            row.getString("app_id"),
            row.getString("branch"),
            row.getString("commit_sha"),
            row.getObject("push_event_id", UUID.class),
            row.getString("trigger"),
            row.getString("provider"),
            row.getLong("installation_id"),
            row.getLong("repo_id"),
            row.getString("repo"),
            row.getString("root_directory"),
            row.getInt("attempt"),
            row.getObject("start_deadline", OffsetDateTime.class).toInstant(),
            PriorityClass.fromRank(row.getShort("priority_class")),
            row.getObject("enqueued_at", OffsetDateTime.class).toInstant(),
            row.getObject("dispatched_at", OffsetDateTime.class).toInstant(),
            row.getString("traceparent"));

    private final JdbcClient jdbc;
    private final TransitionLog transitionLog;
    private final QueueMetrics metrics;

    BuildTransitions(JdbcClient jdbc, TransitionLog transitionLog, QueueMetrics metrics) {
        this.jdbc = jdbc;
        this.transitionLog = transitionLog;
        this.metrics = metrics;
    }

    /** Inserts the build waiting for a slot. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(NewBuild build) {
        insert(build, BuildStatus.QUEUED, null);
        logged(build.buildId(), build.orgId(), build.appId(), null, BuildStatus.QUEUED, null, 0);
    }

    /** Inserts the build already finished as {@code REJECTED}: it is recorded, but never waits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reject(NewBuild build) {
        insert(build, BuildStatus.REJECTED, BuildCancelled.REASON_QUEUE_FULL);
        logged(
                build.buildId(),
                build.orgId(),
                build.appId(),
                null,
                BuildStatus.REJECTED,
                BuildCancelled.REASON_QUEUE_FULL,
                0);
        metrics.finished(Outcome.REJECTED, BuildCancelled.REASON_QUEUE_FULL);
    }

    /**
     * {@code QUEUED → SUPERSEDED}, pointing at the build that replaces it. That build may be inserted later in the
     * same transaction: the foreign key is checked at commit.
     *
     * @return false if the build was no longer waiting
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean supersede(String orgId, UUID buildId, UUID supersededBy) {
        Optional<String> appId = jdbc.sql(SUPERSEDE)
                .param("orgId", orgId)
                .param("buildId", buildId)
                .param("supersededBy", supersededBy)
                .query(String.class)
                .optional();
        appId.ifPresent(app -> {
            String reason = BuildCancelled.REASON_SUPERSEDED;
            logged(buildId, orgId, app, BuildStatus.QUEUED, BuildStatus.SUPERSEDED, reason, 0);
            metrics.finished(Outcome.SUPERSEDED, reason);
        });
        return appId.isPresent();
    }

    /**
     * {@code QUEUED → CANCELLED} by a person, recording who. Call with the org row locked.
     *
     * @return false if the build was no longer waiting
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean cancel(String orgId, UUID buildId, String reason, String cancelledBy) {
        Optional<String> appId = jdbc.sql(CANCEL)
                .param("orgId", orgId)
                .param("buildId", buildId)
                .param("reason", reason)
                .param("cancelledBy", cancelledBy)
                .query(String.class)
                .optional();
        appId.ifPresent(app -> cancelled(buildId, orgId, app, reason));
        return appId.isPresent();
    }

    /** {@code QUEUED → CANCELLED} for every waiting build of the org, oldest first. Call with the org row locked. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<CancelledBuild> cancelAllQueued(String orgId, String reason) {
        return cancelAll(jdbc.sql(CANCEL_ALL_QUEUED_OF_ORG).param("orgId", orgId), reason);
    }

    /** {@code QUEUED → CANCELLED} for every waiting build of the app, oldest first. Call with the org row locked. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<CancelledBuild> cancelAllQueued(String orgId, String appId, String reason) {
        return cancelAll(
                jdbc.sql(CANCEL_ALL_QUEUED_OF_APP).param("orgId", orgId).param("appId", appId), reason);
    }

    /**
     * {@code QUEUED → DISPATCHED}: the next attempt, due to start within {@code startTimeout} of database {@code now()}.
     * Call with the org row locked.
     *
     * @return empty if the build was no longer waiting, because a cancellation or a teardown got there first
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<DispatchedBuild> dispatch(String orgId, UUID buildId, Duration startTimeout) {
        Optional<DispatchedBuild> dispatched = jdbc.sql(DISPATCH)
                .param("orgId", orgId)
                .param("buildId", buildId)
                .param("startTimeoutMillis", startTimeout.toMillis())
                .query(DISPATCHED_BUILD)
                .optional();
        dispatched.ifPresent(build -> dispatched(build, BuildStatus.QUEUED));
        return dispatched;
    }

    /**
     * {@code DISPATCHED → RUNNING}, leased for {@code lease} from database {@code now()}. Every key must match, so a
     * started event can't land on another tenant's build or another app's.
     *
     * @return the status the build left, or empty if it was not {@code DISPATCHED}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BuildStatus> start(String orgId, String appId, UUID buildId, Duration lease) {
        Optional<Moved> moved = guarded(START, orgId, appId, buildId)
                .param("leaseMillis", lease.toMillis())
                .query(MOVED)
                .optional();
        moved.ifPresent(started -> {
            logged(buildId, orgId, appId, started.from(), BuildStatus.RUNNING, null, started.attempt());
            metrics.started(started.dispatchedAt(), started.startedAt());
        });
        return moved.map(Moved::from);
    }

    /**
     * {@code DISPATCHED | RUNNING | EXPIRED → SUCCEEDED}. A late result for an expired build is still a fact about a build
     * that ran.
     *
     * @return the status the build left, or empty if it had none of those statuses
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BuildStatus> succeed(String orgId, String appId, UUID buildId) {
        Optional<Moved> moved =
                guarded(SUCCEED, orgId, appId, buildId).query(MOVED).optional();
        moved.ifPresent(result -> reported(buildId, orgId, appId, result, BuildStatus.SUCCEEDED, Outcome.SUCCEEDED));
        return moved.map(Moved::from);
    }

    /**
     * {@code DISPATCHED | RUNNING | EXPIRED → FAILED}, keeping the first {@value #MAX_FAILURE_REASON_LENGTH} characters
     * of {@code reason}.
     *
     * @return the status the build left, or empty if it had none of those statuses
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BuildStatus> fail(String orgId, String appId, UUID buildId, String reason) {
        Optional<Moved> moved = guarded(FAIL, orgId, appId, buildId)
                .param("reason", reason)
                .param("maxReasonLength", MAX_FAILURE_REASON_LENGTH)
                .query(MOVED)
                .optional();
        moved.ifPresent(result -> reported(buildId, orgId, appId, result, BuildStatus.FAILED, Outcome.FAILED));
        return moved.map(Moved::from);
    }

    /**
     * {@code QUEUED → EXPIRED} ({@code QUEUE_TIMEOUT}) for up to {@code limit} builds that have waited longer than
     * {@code maxAge}.
     */
    @CrossTenant("the lease sweeper enforces deadlines for every org")
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ExpiredBuild> expireQueued(Duration maxAge, int limit) {
        return expire(
                EXPIRE_QUEUED,
                BuildStatus.QUEUED,
                BuildCancelled.REASON_QUEUE_TIMEOUT,
                limit,
                Map.of("maxAgeMillis", maxAge.toMillis()));
    }

    /**
     * {@code DISPATCHED → EXPIRED} ({@code START_TIMEOUT}) for up to {@code limit} builds past their start deadline
     * with no attempts left.
     */
    @CrossTenant("the lease sweeper enforces deadlines for every org")
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ExpiredBuild> expireUnstarted(int maxAttempts, int limit) {
        return expire(
                EXPIRE_UNSTARTED,
                BuildStatus.DISPATCHED,
                BuildCancelled.REASON_START_TIMEOUT,
                limit,
                Map.of("maxAttempts", maxAttempts));
    }

    /** {@code RUNNING → EXPIRED} ({@code LEASE_EXPIRED}) for up to {@code limit} builds past their run lease. */
    @CrossTenant("the lease sweeper enforces deadlines for every org")
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ExpiredBuild> expireLeases(int limit) {
        return expire(EXPIRE_LEASES, BuildStatus.RUNNING, BuildCancelled.REASON_LEASE_EXPIRED, limit, Map.of());
    }

    /**
     * {@code DISPATCHED → DISPATCHED} for up to {@code limit} builds past their start deadline with attempts left: the
     * same build as the next attempt, due within {@code startTimeout} of database {@code now()}. The build keeps its
     * slot throughout.
     */
    @CrossTenant("the lease sweeper enforces deadlines for every org")
    @Transactional(propagation = Propagation.MANDATORY)
    public List<DispatchedBuild> redispatch(int maxAttempts, Duration startTimeout, int limit) {
        List<DispatchedBuild> redispatched = jdbc.sql(REDISPATCH)
                .param("maxAttempts", maxAttempts)
                .param("startTimeoutMillis", startTimeout.toMillis())
                .param("limit", limit)
                .query(DISPATCHED_BUILD)
                .list();
        redispatched.forEach(build -> dispatched(build, BuildStatus.DISPATCHED));
        return redispatched;
    }

    /**
     * Moves every start deadline and run lease due within {@code grace} out to {@code grace} from database
     * {@code now()}. A published attempt's {@code startBy} is unchanged: this is only how long the queue waits.
     *
     * @return how many builds' deadlines moved
     */
    @CrossTenant("the lease sweeper re-applies the grace for every org after an outage")
    @Transactional(propagation = Propagation.MANDATORY)
    public int extendDeadlines(Duration grace) {
        long graceMillis = grace.toMillis();
        return jdbc.sql(EXTEND_START_DEADLINES)
                        .param("graceMillis", graceMillis)
                        .update()
                + jdbc.sql(EXTEND_LEASES).param("graceMillis", graceMillis).update();
    }

    private static String expiring(String due, String deadline) {
        return EXPIRE.replace("{due}", due).replace("{deadline}", deadline);
    }

    private List<ExpiredBuild> expire(String sql, BuildStatus from, String reason, int limit, Map<String, ?> params) {
        return jdbc.sql(sql)
                .params(params)
                .param("reason", reason)
                .param("limit", limit)
                .query((row, rowNumber) -> {
                    ExpiredBuild build = new ExpiredBuild(
                            row.getObject("build_id", UUID.class),
                            row.getString("org_id"),
                            row.getString("app_id"),
                            row.getString("commit_sha"),
                            row.getObject("push_event_id", UUID.class),
                            from,
                            reason,
                            row.getString("traceparent"));
                    logged(
                            build.buildId(),
                            build.orgId(),
                            build.appId(),
                            from,
                            BuildStatus.EXPIRED,
                            reason,
                            row.getInt("attempt"));
                    metrics.finished(Outcome.EXPIRED, reason);
                    return build;
                })
                .list();
    }

    private List<CancelledBuild> cancelAll(JdbcClient.StatementSpec statement, String reason) {
        return statement
                .param("reason", reason)
                .query((row, rowNumber) -> {
                    CancelledBuild build = new CancelledBuild(
                            row.getObject("build_id", UUID.class),
                            row.getString("org_id"),
                            row.getString("app_id"),
                            row.getString("commit_sha"),
                            row.getObject("push_event_id", UUID.class),
                            reason);
                    cancelled(build.buildId(), build.orgId(), build.appId(), reason);
                    return build;
                })
                .list();
    }

    private void cancelled(UUID buildId, String orgId, String appId, String reason) {
        logged(buildId, orgId, appId, BuildStatus.QUEUED, BuildStatus.CANCELLED, reason, 0);
        metrics.finished(Outcome.CANCELLED, reason);
    }

    private void dispatched(DispatchedBuild build, BuildStatus from) {
        logged(build.buildId(), build.orgId(), build.appId(), from, BuildStatus.DISPATCHED, null, build.attempt());
        metrics.dispatched(build.attempt(), priority(build.priorityClass()), build.enqueuedAt(), build.dispatchedAt());
    }

    /** A result from build-service; one for a build the queue had already given up on is a late result. */
    private void reported(UUID buildId, String orgId, String appId, Moved result, BuildStatus to, Outcome outcome) {
        logged(buildId, orgId, appId, result.from(), to, null, result.attempt());
        metrics.finished(
                outcome,
                result.from() == BuildStatus.EXPIRED ? QueueMetrics.REASON_LATE_RESULT : QueueMetrics.REASON_NONE);
    }

    private void logged(
            UUID buildId, String orgId, String appId, BuildStatus from, BuildStatus to, String reason, int attempt) {
        transitionLog.record(buildId, orgId, appId, from == null ? null : from.name(), to.name(), reason, attempt);
    }

    private static Priority priority(PriorityClass priorityClass) {
        return switch (priorityClass) {
            case INTERACTIVE -> Priority.INTERACTIVE;
            case AUTOMATIC -> Priority.AUTOMATIC;
        };
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static String guardedFrom(String from, String set) {
        return GUARDED_FROM.replace("{from}", from).replace("{set}", set);
    }

    private JdbcClient.StatementSpec guarded(String sql, String orgId, String appId, UUID buildId) {
        return jdbc.sql(sql).param("orgId", orgId).param("appId", appId).param("buildId", buildId);
    }

    private void insert(NewBuild build, BuildStatus status, String statusReason) {
        jdbc.sql(INSERT)
                .param("buildId", build.buildId())
                .param("orgId", build.orgId())
                .param("appId", build.appId())
                .param("branch", build.branch())
                .param("commitSha", build.commitSha())
                .param("pushEventId", build.pushEventId())
                .param("trigger", build.trigger())
                .param("priorityClass", build.priorityClass().rank())
                .param("provider", build.provider())
                .param("installationId", build.installationId())
                .param("repoId", build.repoId())
                .param("repo", build.repo())
                .param("rootDirectory", build.rootDirectory())
                .param("headCommitMessage", build.headCommitMessage())
                .param("headCommitAuthor", build.headCommitAuthor())
                .param("traceparent", build.traceparent())
                .param("status", status.name())
                .param("statusReason", statusReason)
                .param("finished", status.isTerminal())
                .update();
    }
}
