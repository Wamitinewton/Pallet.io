package io.pallet.gitintegration.retention;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Rows seeded straight into the tables retention sweeps, each aged against the database's {@code now()}, which is the
 * clock the sweeps compare with. Removed again after each test.
 */
final class RetentionRows {

    static final String AGE = "now() - make_interval(secs => ?)";
    static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    private final JdbcTemplate jdbc;
    private final List<UUID> deliveries = new ArrayList<>();
    private final List<UUID> events = new ArrayList<>();
    private final List<UUID> states = new ArrayList<>();

    RetentionRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static double seconds(Duration age) {
        return age.toMillis() / 1000.0;
    }

    UUID delivery(String status, Duration age) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO git_integration.webhook_deliveries (delivery_id, event, payload, status, received_at)"
                        + " VALUES (?, 'push', '{\"pusher\":{\"email\":\"dev@example.com\"}}'::jsonb, ?, " + AGE + ")",
                id,
                status,
                seconds(age));
        deliveries.add(id);
        return id;
    }

    List<UUID> deliveries(String status, Duration age, int count) {
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID());
        }
        jdbc.batchUpdate(
                "INSERT INTO git_integration.webhook_deliveries (delivery_id, event, payload, status, received_at)"
                        + " VALUES (?, 'push', '{}'::jsonb, ?, " + AGE + ")",
                ids,
                500,
                (statement, id) -> {
                    statement.setObject(1, id);
                    statement.setString(2, status);
                    statement.setDouble(3, seconds(age));
                });
        deliveries.addAll(ids);
        return ids;
    }

    boolean deliveryExists(UUID id) {
        return exists("webhook_deliveries", "delivery_id", id);
    }

    boolean payloadNulled(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT payload IS NULL FROM git_integration.webhook_deliveries WHERE delivery_id = ?",
                Boolean.class,
                id));
    }

    /** A {@code PUBLISHED} row when {@code publishedAge} is given; otherwise a {@code PARKED} one the relay leaves. */
    UUID outboxEvent(String orgId, Duration publishedAge, Duration createdAge) {
        UUID eventId = UUID.randomUUID();
        if (publishedAge == null) {
            jdbc.update(
                    "INSERT INTO git_integration.outbox_events (event_id, org_id, event_type, status, created_at)"
                            + " VALUES (?, ?, 'git.push.received', 'PARKED', " + AGE + ")",
                    eventId,
                    orgId,
                    seconds(createdAge));
        } else {
            jdbc.update(
                    "INSERT INTO git_integration.outbox_events"
                            + " (event_id, org_id, event_type, status, created_at, published_at)"
                            + " VALUES (?, ?, 'git.push.received', 'PUBLISHED', " + AGE + ", " + AGE + ")",
                    eventId,
                    orgId,
                    seconds(createdAge),
                    seconds(publishedAge));
        }
        return eventId;
    }

    boolean outboxEventExists(UUID eventId) {
        return exists("outbox_events", "event_id", eventId);
    }

    UUID processedEvent(Duration age) {
        UUID eventId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO git_integration.processed_events (event_id, consumer, processed_at)"
                        + " VALUES (?, 'retention-test', " + AGE + ")",
                eventId,
                seconds(age));
        events.add(eventId);
        return eventId;
    }

    boolean processedEventExists(UUID eventId) {
        return exists("processed_events", "event_id", eventId);
    }

    /** A state that expired {@code sinceExpiry} ago. */
    UUID authorizationState(Duration sinceExpiry) {
        UUID nonce = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO git_integration.authorization_states (nonce, purpose, user_id, expires_at)"
                        + " VALUES (?, 'AUTHORIZE', 'user-retention', " + AGE + ")",
                nonce,
                seconds(sinceExpiry));
        states.add(nonce);
        return nonce;
    }

    boolean authorizationStateExists(UUID nonce) {
        return exists("authorization_states", "nonce", nonce);
    }

    String manualBuildRequest(String orgId, UUID appId, Duration age) {
        String key = "key-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO git_integration.manual_build_requests"
                        + " (app_id, idempotency_key, org_id, request_hash, event_id, branch, commit_sha, requested_by,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, 'main', ?, 'user-retention', " + AGE + ")",
                appId,
                key,
                orgId,
                "a".repeat(64),
                UUID.randomUUID(),
                SHA,
                seconds(age));
        return key;
    }

    boolean manualBuildRequestExists(String key) {
        return exists("manual_build_requests", "idempotency_key", key);
    }

    /** {@code reported} makes the reporter's delivered revision match the desired one. */
    UUID checkRun(String orgId, String state, boolean reported, Duration age) {
        UUID appId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO git_integration.check_runs"
                        + " (app_id, commit_sha, org_id, desired_state, desired_conclusion, desired_phase,"
                        + " desired_revision, reported_revision, last_reported_state, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'BUILD_FINISHED', 2, ?, ?, " + AGE + ")",
                appId,
                SHA,
                orgId,
                state,
                "completed".equals(state) ? "success" : null,
                reported ? 2 : 1,
                reported ? state : null,
                seconds(age));
        return appId;
    }

    boolean checkRunExists(UUID appId) {
        return exists("check_runs", "app_id", appId);
    }

    void disconnectRepoLink(UUID appId, Duration age) {
        jdbc.update(
                "UPDATE git_integration.repo_links SET status = 'DISCONNECTED', disconnect_reason = 'UNLINKED_BY_USER',"
                        + " disconnected_at = " + AGE + " WHERE app_id = ?",
                seconds(age),
                appId);
    }

    void branchHead(UUID appId) {
        jdbc.update(
                "INSERT INTO git_integration.branch_heads (app_id, branch, head_sha) VALUES (?, 'main', ?)",
                appId,
                SHA);
    }

    int branchHeads(UUID appId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM git_integration.branch_heads WHERE app_id = ?", Integer.class, appId);
    }

    boolean repoLinkExists(UUID appId) {
        return exists("repo_links", "app_id", appId);
    }

    void unlinkInstallation(long installationId, String orgId, Duration age) {
        jdbc.update(
                "UPDATE git_integration.installation_links SET status = 'UNLINKED', unlinked_at = " + AGE
                        + " WHERE installation_id = ? AND org_id = ?",
                seconds(age),
                installationId,
                orgId);
    }

    boolean installationLinkExists(long installationId, String orgId) {
        return jdbc.queryForObject(
                        "SELECT count(*) FROM git_integration.installation_links"
                                + " WHERE installation_id = ? AND org_id = ?",
                        Integer.class,
                        installationId,
                        orgId)
                > 0;
    }

    void deleteInstallation(long installationId, Duration age) {
        jdbc.update(
                "UPDATE git_integration.installations SET status = 'DELETED', deleted_at = " + AGE
                        + " WHERE installation_id = ?",
                seconds(age),
                installationId);
    }

    void installationRepository(long installationId, long repoId) {
        jdbc.update(
                "INSERT INTO git_integration.installation_repositories"
                        + " (installation_id, repo_id, full_name, default_branch, is_private)"
                        + " VALUES (?, ?, 'octo-org/api', 'main', true)",
                installationId,
                repoId);
    }

    int installationRepositories(long installationId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM git_integration.installation_repositories WHERE installation_id = ?",
                Integer.class,
                installationId);
    }

    boolean installationExists(long installationId) {
        return exists("installations", "installation_id", installationId);
    }

    void deleteApp(UUID appId, Duration age) {
        jdbc.update(
                "UPDATE git_integration.apps SET status = 'DELETED', updated_at = " + AGE + " WHERE app_id = ?",
                seconds(age),
                appId);
    }

    boolean appExists(UUID appId) {
        return exists("apps", "app_id", appId);
    }

    void deletedOrg(String orgId, Duration age) {
        jdbc.update(
                "INSERT INTO git_integration.deleted_orgs (org_id, deleted_at) VALUES (?, " + AGE + ")",
                orgId,
                seconds(age));
    }

    boolean deletedOrgExists(String orgId) {
        return exists("deleted_orgs", "org_id", orgId);
    }

    void removeAll() {
        deliveries.forEach(
                id -> jdbc.update("DELETE FROM git_integration.webhook_deliveries WHERE delivery_id = ?", id));
        events.forEach(id -> jdbc.update("DELETE FROM git_integration.processed_events WHERE event_id = ?", id));
        states.forEach(id -> jdbc.update("DELETE FROM git_integration.authorization_states WHERE nonce = ?", id));
        deliveries.clear();
        events.clear();
        states.clear();
    }

    private boolean exists(String table, String column, Object value) {
        return jdbc.queryForObject(
                        "SELECT count(*) FROM git_integration." + table + " WHERE " + column + " = ?",
                        Integer.class,
                        value)
                > 0;
    }
}
