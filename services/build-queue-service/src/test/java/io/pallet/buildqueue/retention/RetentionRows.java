package io.pallet.buildqueue.retention;

import io.pallet.buildqueue.build.BuildStatus;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Rows written straight into the tables retention reads, aged relative to database {@code now()}. Every build is on a
 * branch of its own, so any number of them fit beside each other under the coalescing index.
 */
final class RetentionRows {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    private static final String AGO = "now() - ? * interval '1 millisecond'";

    private final JdbcTemplate jdbc;

    RetentionRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Finished {@code age} ago in {@code status}; {@code SUPERSEDED} carries its required reason. */
    UUID finished(String orgId, String appId, BuildStatus status, Duration age) {
        if (!status.isTerminal() && status != BuildStatus.EXPIRED) {
            throw new IllegalArgumentException("not a finished status: " + status);
        }
        UUID buildId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO build_queue.builds
                    (build_id, org_id, app_id, branch, commit_sha, push_event_id, trigger, priority_class, provider,
                     installation_id, repo_id, repo, status, status_reason, enqueued_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, 'WEBHOOK', 1, 'GITHUB', 1, 1, 'octo-org/api', ?, ?,
                        %s - interval '1 hour', %s)
                """.formatted(AGO, AGO),
                buildId,
                orgId,
                appId,
                "branch-" + buildId,
                SHA,
                UUID.randomUUID(),
                status.name(),
                status == BuildStatus.SUPERSEDED ? "SUPERSEDED" : null,
                age.toMillis(),
                age.toMillis());
        return buildId;
    }

    /** {@code count} builds finished {@code age} ago, in one statement. */
    void finished(String orgId, String appId, int count, Duration age) {
        jdbc.update("""
                INSERT INTO build_queue.builds
                    (build_id, org_id, app_id, branch, commit_sha, push_event_id, trigger, priority_class, provider,
                     installation_id, repo_id, repo, status, enqueued_at, finished_at)
                SELECT gen_random_uuid(), ?, ?, 'bulk-' || n, ?, gen_random_uuid(), 'WEBHOOK', 1, 'GITHUB', 1, 1,
                       'octo-org/api', 'SUCCEEDED', %s - interval '1 hour', %s
                  FROM generate_series(1, ?) AS n
                """.formatted(AGO, AGO), orgId, appId, SHA, age.toMillis(), age.toMillis(), count);
    }

    UUID queued(String orgId, String appId, Duration age) {
        UUID buildId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO build_queue.builds
                    (build_id, org_id, app_id, branch, commit_sha, push_event_id, trigger, priority_class, provider,
                     installation_id, repo_id, repo, status, enqueued_at)
                VALUES (?, ?, ?, ?, ?, ?, 'WEBHOOK', 1, 'GITHUB', 1, 1, 'octo-org/api', 'QUEUED', %s)
                """.formatted(AGO), buildId, orgId, appId, "branch-" + buildId, SHA, UUID.randomUUID(), age.toMillis());
        return buildId;
    }

    /** Waiting since {@code age} ago and dispatched then. */
    UUID dispatched(String orgId, String appId, Duration age) {
        UUID buildId = queued(orgId, appId, age);
        jdbc.update(
                "UPDATE build_queue.builds SET status = 'DISPATCHED', attempt = 1, dispatched_at = %s,".formatted(AGO)
                        + " start_deadline = now() + interval '5 minutes' WHERE build_id = ?",
                age.toMillis(),
                buildId);
        return buildId;
    }

    /** Waiting since {@code age} ago and running since then. */
    UUID running(String orgId, String appId, Duration age) {
        UUID buildId = dispatched(orgId, appId, age);
        jdbc.update(
                "UPDATE build_queue.builds SET status = 'RUNNING', started_at = %s,".formatted(AGO)
                        + " lease_expires_at = now() + interval '30 minutes' WHERE build_id = ?",
                age.toMillis(),
                buildId);
        return buildId;
    }

    void pointSupersededBy(UUID buildId, UUID supersededBy) {
        jdbc.update("UPDATE build_queue.builds SET superseded_by = ? WHERE build_id = ?", supersededBy, buildId);
    }

    boolean buildExists(UUID buildId) {
        return exists("SELECT count(*) FROM build_queue.builds WHERE build_id = ?", buildId);
    }

    UUID supersededBy(UUID buildId) {
        return jdbc.queryForObject(
                "SELECT superseded_by FROM build_queue.builds WHERE build_id = ?", UUID.class, buildId);
    }

    int buildCount(String appId) {
        return jdbc.queryForObject("SELECT count(*) FROM build_queue.builds WHERE app_id = ?", Integer.class, appId);
    }

    private void deletedAgo(String table, String idColumn, String id, Duration age) {
        jdbc.update(
                "UPDATE build_queue.%s SET status = 'DELETED', deleted_at = %s WHERE %s = ?"
                        .formatted(table, AGO, idColumn),
                age.toMillis(),
                id);
    }

    void appDeletedAgo(String appId, Duration age) {
        deletedAgo("apps", "app_id", appId, age);
    }

    void orgDeletedAgo(String orgId, Duration age) {
        deletedAgo("orgs", "org_id", orgId, age);
    }

    /** An active row last touched {@code age} ago: old, but never a tombstone. */
    void touchedAgo(String table, String idColumn, String id, Duration age) {
        jdbc.update(
                "UPDATE build_queue.%s SET updated_at = %s WHERE %s = ?".formatted(table, AGO, idColumn),
                age.toMillis(),
                id);
    }

    boolean appExists(String appId) {
        return exists("SELECT count(*) FROM build_queue.apps WHERE app_id = ?", appId);
    }

    boolean orgExists(String orgId) {
        return exists("SELECT count(*) FROM build_queue.orgs WHERE org_id = ?", orgId);
    }

    int membershipCount(String orgId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM build_queue.org_memberships WHERE org_id = ?", Integer.class, orgId);
    }

    UUID outboxEvent(String orgId, String status, Duration age) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO build_queue.outbox_events (event_id, org_id, event_type, status, created_at, published_at)
                VALUES (?, ?, 'BuildQueued', ?, %s, CASE WHEN ? = 'PUBLISHED' THEN %s END)
                """.formatted(AGO, AGO), eventId, orgId, status, age.toMillis(), status, age.toMillis());
        return eventId;
    }

    boolean outboxEventExists(UUID eventId) {
        return exists("SELECT count(*) FROM build_queue.outbox_events WHERE event_id = ?", eventId);
    }

    UUID processedEvent(Duration age) {
        UUID eventId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO build_queue.processed_events (event_id, consumer, processed_at) VALUES (?, 'retention', %s)"
                        .formatted(AGO),
                eventId,
                age.toMillis());
        return eventId;
    }

    boolean processedEventExists(UUID eventId) {
        return exists("SELECT count(*) FROM build_queue.processed_events WHERE event_id = ?", eventId);
    }

    void removeOutboxEvents(String orgId) {
        jdbc.update("DELETE FROM build_queue.outbox_events WHERE org_id = ?", orgId);
    }

    private boolean exists(String sql, Object id) {
        return jdbc.queryForObject(sql, Integer.class, id) > 0;
    }
}
