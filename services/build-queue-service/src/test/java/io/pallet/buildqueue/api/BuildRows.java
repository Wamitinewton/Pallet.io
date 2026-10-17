package io.pallet.buildqueue.api;

import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.build.PriorityClass;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Builds written straight into the table in any status, with the timestamps that status's checks require. Ages are
 * relative to database {@code now()}, so order-by-time assertions hold however long the test takes.
 */
final class BuildRows {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    private final JdbcTemplate jdbc;

    BuildRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void quiesce() {
        jdbc.update("DELETE FROM build_queue.builds");
    }

    UUID queued(String orgId, String appId, PriorityClass priority, Duration age) {
        return insert(orgId, appId, BuildStatus.QUEUED, priority, age);
    }

    /** In flight since {@code age} ago; enqueued a minute before that. */
    UUID inFlight(String orgId, String appId, BuildStatus status, Duration age) {
        if (!status.isInFlight()) {
            throw new IllegalArgumentException("not an in-flight status: " + status);
        }
        UUID buildId = insert(orgId, appId, BuildStatus.QUEUED, PriorityClass.AUTOMATIC, age.plusMinutes(1));
        String running = status == BuildStatus.RUNNING
                ? ", started_at = now() - ?::interval, lease_expires_at = now() + interval '30 minutes'"
                : "";
        String sql = "UPDATE build_queue.builds SET status = ?, attempt = 1, dispatched_at = now() - ?::interval,"
                + " start_deadline = now() + interval '5 minutes'" + running + " WHERE build_id = ?";
        if (status == BuildStatus.RUNNING) {
            jdbc.update(sql, status.name(), interval(age), interval(age), buildId);
        } else {
            jdbc.update(sql, status.name(), interval(age), buildId);
        }
        return buildId;
    }

    /** Left the queue in {@code status}; {@code SUPERSEDED} carries its required reason. */
    UUID finished(String orgId, String appId, BuildStatus status, Duration age) {
        if (!status.isTerminal() && status != BuildStatus.EXPIRED) {
            throw new IllegalArgumentException("not a finished status: " + status);
        }
        UUID buildId = insert(orgId, appId, BuildStatus.QUEUED, PriorityClass.AUTOMATIC, age);
        jdbc.update(
                "UPDATE build_queue.builds SET status = ?, status_reason = ?, finished_at = now() WHERE build_id = ?",
                status.name(),
                status == BuildStatus.SUPERSEDED ? "SUPERSEDED" : null,
                buildId);
        return buildId;
    }

    /** Frees an in-flight build's slot as a result would. */
    void succeed(UUID buildId) {
        jdbc.update(
                "UPDATE build_queue.builds SET status = 'SUCCEEDED', finished_at = now(), version = version + 1"
                        + " WHERE build_id = ?",
                buildId);
    }

    /** On its own branch, so any number of waiting builds of one app fit under the coalescing index. */
    private UUID insert(String orgId, String appId, BuildStatus status, PriorityClass priority, Duration age) {
        UUID buildId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO build_queue.builds
                    (build_id, org_id, app_id, branch, commit_sha, push_event_id, trigger, priority_class,
                     provider, installation_id, repo_id, repo, status, enqueued_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'GITHUB', 1, 1, 'octo-org/api', ?, now() - ?::interval)
                """,
                buildId,
                orgId,
                appId,
                "branch-" + buildId,
                SHA,
                UUID.randomUUID(),
                priority == PriorityClass.INTERACTIVE ? "MANUAL" : "WEBHOOK",
                priority.rank(),
                status.name(),
                interval(age));
        return buildId;
    }

    private static String interval(Duration duration) {
        return duration.toMillis() + " milliseconds";
    }
}
