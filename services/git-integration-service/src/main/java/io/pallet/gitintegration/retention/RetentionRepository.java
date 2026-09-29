package io.pallet.gitintegration.retention;

import io.pallet.gitintegration.config.CrossTenant;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * One batch of one retention sweep per method, oldest rows first. Every candidate is locked {@code SKIP LOCKED}, so a
 * row another transaction holds is left for a later run, and every row something still references is guarded with
 * {@code NOT EXISTS} rather than cascaded from its parent.
 */
@Repository
class RetentionRepository {

    private static final String CUTOFF = "now() - make_interval(secs => :windowSeconds)";

    private final JdbcClient jdbc;

    RetentionRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @CrossTenant("sets a transaction-local setting and reads no org's rows")
    void applyLockTimeout(Duration timeout) {
        jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)")
                .param("timeout", timeout.toMillis() + "ms")
                .query(String.class)
                .single();
    }

    /** A {@code RECEIVED} delivery is still owed processing, so it keeps its payload. */
    @CrossTenant("deliveries are keyed by GitHub, not by org; retention applies to every one alike")
    int nullDeliveryPayloads(Duration window, int batchSize) {
        return update("""
                UPDATE git_integration.webhook_deliveries SET payload = NULL
                WHERE delivery_id IN (
                    SELECT delivery_id FROM git_integration.webhook_deliveries
                    WHERE payload IS NOT NULL AND status <> 'RECEIVED' AND received_at < {cutoff}
                    ORDER BY received_at
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED)
                """, window, batchSize);
    }

    /** {@code RECEIVED} and {@code PARKED} deliveries are never deleted, however old. */
    @CrossTenant("deliveries are keyed by GitHub, not by org; retention applies to every one alike")
    int deleteDeliveries(Duration window, int batchSize) {
        return update("""
                DELETE FROM git_integration.webhook_deliveries
                WHERE delivery_id IN (
                    SELECT delivery_id FROM git_integration.webhook_deliveries
                    WHERE status IN ('PROCESSED', 'IGNORED') AND received_at < {cutoff}
                    ORDER BY received_at
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED)
                """, window, batchSize);
    }

    @CrossTenant("expired authorization states are removed for every user and org alike")
    int deleteAuthorizationStates(Duration window, int batchSize) {
        return update("""
                DELETE FROM git_integration.authorization_states
                WHERE nonce IN (
                    SELECT nonce FROM git_integration.authorization_states
                    WHERE expires_at < {cutoff}
                    ORDER BY expires_at
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED)
                """, window, batchSize);
    }

    @CrossTenant("retention applies to every org's manual build requests alike")
    int deleteManualBuildRequests(Duration window, int batchSize) {
        return update("""
                DELETE FROM git_integration.manual_build_requests
                WHERE (app_id, idempotency_key) IN (
                    SELECT app_id, idempotency_key FROM git_integration.manual_build_requests
                    WHERE created_at < {cutoff}
                    ORDER BY created_at
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED)
                """, window, batchSize);
    }

    /** Only completed check runs with nothing left to report. */
    @CrossTenant("retention applies to every org's check runs alike")
    int deleteCheckRuns(Duration window, int batchSize) {
        return update("""
                DELETE FROM git_integration.check_runs
                WHERE (app_id, commit_sha) IN (
                    SELECT app_id, commit_sha FROM git_integration.check_runs
                    WHERE desired_state = 'completed' AND reported_revision = desired_revision
                      AND updated_at < {cutoff}
                    ORDER BY updated_at
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED)
                """, window, batchSize);
    }

    /** A link's branch heads are its own rows and go with it. */
    @CrossTenant("retention applies to every org's disconnected repo links alike")
    int deleteRepoLinks(Duration window, int batchSize) {
        return update("""
                DELETE FROM git_integration.repo_links
                WHERE app_id IN (
                    SELECT app_id FROM git_integration.repo_links
                    WHERE status = 'DISCONNECTED' AND disconnected_at < {cutoff}
                    ORDER BY disconnected_at
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED)
                """, window, batchSize);
    }

    @CrossTenant("retention applies to every org's unlinked installation links alike")
    int deleteInstallationLinks(Duration window, int batchSize) {
        return update("""
                DELETE FROM git_integration.installation_links
                WHERE (installation_id, org_id) IN (
                    SELECT l.installation_id, l.org_id FROM git_integration.installation_links l
                    WHERE l.status = 'UNLINKED' AND l.unlinked_at < {cutoff}
                      AND NOT EXISTS (
                          SELECT 1 FROM git_integration.repo_links r
                          WHERE r.installation_id = l.installation_id AND r.org_id = l.org_id)
                    ORDER BY l.unlinked_at
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED)
                """, window, batchSize);
    }

    @CrossTenant("retention applies to every org's deleted apps alike")
    int deleteApps(Duration window, int batchSize) {
        return update("""
                DELETE FROM git_integration.apps
                WHERE app_id IN (
                    SELECT a.app_id FROM git_integration.apps a
                    WHERE a.status = 'DELETED' AND a.updated_at < {cutoff}
                      AND NOT EXISTS (SELECT 1 FROM git_integration.repo_links r WHERE r.app_id = a.app_id)
                    ORDER BY a.updated_at
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED)
                """, window, batchSize);
    }

    /** Deletes each installation with its repository rows, in one statement. */
    @CrossTenant("installations are keyed by GitHub, not by org; retention applies to every one alike")
    int deleteInstallations(Duration window, int batchSize) {
        return update("""
                WITH doomed AS (
                    SELECT i.installation_id FROM git_integration.installations i
                    WHERE i.status = 'DELETED' AND i.deleted_at < {cutoff}
                      AND NOT EXISTS (
                          SELECT 1 FROM git_integration.installation_links l
                          WHERE l.installation_id = i.installation_id)
                    ORDER BY i.deleted_at
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED),
                repositories AS (
                    DELETE FROM git_integration.installation_repositories r
                    USING doomed d
                    WHERE r.installation_id = d.installation_id)
                DELETE FROM git_integration.installations i
                USING doomed d
                WHERE i.installation_id = d.installation_id
                """, window, batchSize);
    }

    /** An org with a membership still active in the read model keeps its row, so the gate keeps refusing it. */
    @CrossTenant("retention applies to every deleted org alike")
    int deleteDeletedOrgs(Duration window, int batchSize) {
        return update("""
                DELETE FROM git_integration.deleted_orgs
                WHERE org_id IN (
                    SELECT d.org_id FROM git_integration.deleted_orgs d
                    WHERE d.deleted_at < {cutoff}
                      AND NOT EXISTS (
                          SELECT 1 FROM git_integration.org_memberships m
                          WHERE m.org_id = d.org_id AND m.status = 'ACTIVE')
                    ORDER BY d.deleted_at
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED)
                """, window, batchSize);
    }

    private int update(String sql, Duration window, int batchSize) {
        return jdbc.sql(sql.replace("{cutoff}", CUTOFF))
                .param("windowSeconds", window.toMillis() / 1000.0)
                .param("batchSize", batchSize)
                .update();
    }
}
