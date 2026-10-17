package io.pallet.buildqueue.retention;

import io.pallet.buildqueue.config.CrossTenant;
import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One batch of one retention sweep per method, oldest rows first. Candidates are locked {@code SKIP LOCKED}, so a row
 * a listener or the dispatcher holds is left for a later run, and a row something still references is guarded with
 * {@code NOT EXISTS} rather than cascaded from.
 */
@Repository
class RetentionRepository {

    /** What one org batch removed: the tombstones and the memberships that went with them. */
    record OrgPurge(int orgs, int memberships) {}

    private static final String CUTOFF = "now() - :windowMillis * interval '1 millisecond'";

    /**
     * Never {@code QUEUED}, {@code DISPATCHED} or {@code RUNNING}, however old: the lease sweeper owns those. The batch is
     * a CTE because Postgres evaluates one exactly once: an {@code IN (... LIMIT)} subquery may be rescanned under some
     * plans and delete past the batch size.
     */
    private static final String DELETE_TERMINAL_BUILDS = """
            WITH batch AS (
                SELECT build_id FROM build_queue.builds
                 WHERE finished_at < {cutoff}
                   AND status IN ('SUCCEEDED', 'FAILED', 'SUPERSEDED', 'CANCELLED', 'REJECTED', 'EXPIRED')
                 ORDER BY finished_at
                 LIMIT :batchSize
                   FOR UPDATE SKIP LOCKED)
            DELETE FROM build_queue.builds b
             USING batch
             WHERE b.build_id = batch.build_id
            """;

    private static final String DELETE_APP_TOMBSTONES = """
            WITH batch AS (
                SELECT a.app_id FROM build_queue.apps a
                 WHERE a.status = 'DELETED' AND a.deleted_at < {cutoff}
                   AND NOT EXISTS (SELECT 1 FROM build_queue.builds b
                                    WHERE b.org_id = a.org_id AND b.app_id = a.app_id)
                 ORDER BY a.deleted_at
                 LIMIT :batchSize
                   FOR UPDATE SKIP LOCKED)
            DELETE FROM build_queue.apps a
             USING batch
             WHERE a.app_id = batch.app_id
            """;

    /**
     * An org with a membership still {@code ACTIVE} keeps its tombstone, since the tombstone is what makes the gate
     * refuse that member.
     */
    private static final String DELETE_ORG_TOMBSTONES = """
            WITH doomed AS (
                SELECT o.org_id FROM build_queue.orgs o
                 WHERE o.status = 'DELETED' AND o.deleted_at < {cutoff}
                   AND NOT EXISTS (SELECT 1 FROM build_queue.apps a WHERE a.org_id = o.org_id)
                   AND NOT EXISTS (SELECT 1 FROM build_queue.builds b WHERE b.org_id = o.org_id)
                   AND NOT EXISTS (SELECT 1 FROM build_queue.org_memberships m
                                    WHERE m.org_id = o.org_id AND m.status = 'ACTIVE')
                 ORDER BY o.deleted_at
                 LIMIT :batchSize
                   FOR UPDATE SKIP LOCKED),
            memberships AS (
                DELETE FROM build_queue.org_memberships m
                 USING doomed d
                 WHERE m.org_id = d.org_id
                RETURNING 1),
            orgs AS (
                DELETE FROM build_queue.orgs o
                 USING doomed d
                 WHERE o.org_id = d.org_id
                RETURNING 1)
            SELECT (SELECT count(*) FROM orgs) AS orgs, (SELECT count(*) FROM memberships) AS memberships
            """;

    private final JdbcClient jdbc;

    RetentionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @CrossTenant("sets a transaction-local setting and reads no org's rows")
    @Transactional(propagation = Propagation.MANDATORY)
    void applyLockTimeout(Duration timeout) {
        jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)")
                .param("timeout", timeout.toMillis() + "ms")
                .query(String.class)
                .single();
    }

    @CrossTenant("retention applies to every org's finished builds alike")
    @Transactional(propagation = Propagation.MANDATORY)
    int deleteTerminalBuilds(Duration window, int batchSize) {
        return statement(DELETE_TERMINAL_BUILDS, window, batchSize).update();
    }

    @CrossTenant("retention applies to every org's deleted apps alike")
    @Transactional(propagation = Propagation.MANDATORY)
    int deleteAppTombstones(Duration window, int batchSize) {
        return statement(DELETE_APP_TOMBSTONES, window, batchSize).update();
    }

    @CrossTenant("retention applies to every deleted org alike")
    @Transactional(propagation = Propagation.MANDATORY)
    OrgPurge deleteOrgTombstones(Duration window, int batchSize) {
        return statement(DELETE_ORG_TOMBSTONES, window, batchSize)
                .query((row, rowNumber) -> new OrgPurge(row.getInt("orgs"), row.getInt("memberships")))
                .single();
    }

    private JdbcClient.StatementSpec statement(String sql, Duration window, int batchSize) {
        return jdbc.sql(sql.replace("{cutoff}", CUTOFF))
                .param("windowMillis", window.toMillis())
                .param("batchSize", batchSize);
    }
}
