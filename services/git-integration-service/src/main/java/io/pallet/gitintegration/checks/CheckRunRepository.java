package io.pallet.gitintegration.checks;

import io.pallet.gitintegration.checks.DesiredCheck.Conclusion;
import io.pallet.gitintegration.checks.DesiredCheck.Phase;
import io.pallet.gitintegration.config.CrossTenant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code check_runs}. Every write to a claimed row is guarded by the {@code desired_revision} it was claimed at, so a
 * desire recorded while GitHub was being called is never marked delivered, retried late, or given up. Every deadline
 * is computed from the database's {@code now()}.
 */
@Repository
public class CheckRunRepository {

    private final JdbcClient jdbc;

    CheckRunRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** A claimed row, with where to report it: null when its link, app, or installation is no longer active. */
    record Claimed(CheckRun checkRun, Target target) {}

    record Target(long installationId, long repoId, String appSlug) {}

    /** @return whether the row was created; false when the app already has one for the commit, in any org */
    boolean insertIfAbsent(String orgId, UUID appId, String commitSha, DesiredCheck desired) {
        return jdbc.sql("""
                        INSERT INTO git_integration.check_runs
                            (app_id, commit_sha, org_id, desired_state, desired_conclusion, desired_details_url,
                             desired_summary, desired_phase, desired_revision, attempts, next_attempt_at, updated_at)
                        VALUES (:appId, :commitSha, :orgId, :state, :conclusion, :detailsUrl, :summary, :phase, 1, 0,
                                now(), now())
                        ON CONFLICT (app_id, commit_sha) DO NOTHING
                        """)
                        .param("orgId", orgId)
                        .param("appId", appId)
                        .param("commitSha", commitSha)
                        .params(desiredParams(desired))
                        .update()
                == 1;
    }

    /** @return the org's row, locked until the transaction ends */
    Optional<CheckRun> lock(String orgId, UUID appId, String commitSha) {
        return jdbc.sql("""
                        SELECT * FROM git_integration.check_runs
                         WHERE org_id = :orgId AND app_id = :appId AND commit_sha = :commitSha
                           FOR UPDATE
                        """)
                .param("orgId", orgId)
                .param("appId", appId)
                .param("commitSha", commitSha)
                .query((rs, row) -> checkRun(rs))
                .optional();
    }

    public Optional<CheckRun> find(String orgId, UUID appId, String commitSha) {
        return jdbc.sql("""
                        SELECT * FROM git_integration.check_runs
                         WHERE org_id = :orgId AND app_id = :appId AND commit_sha = :commitSha
                        """)
                .param("orgId", orgId)
                .param("appId", appId)
                .param("commitSha", commitSha)
                .query((rs, row) -> checkRun(rs))
                .optional();
    }

    /** Replaces the desire, makes the row due now, and starts its attempts over. */
    void updateDesired(String orgId, UUID appId, String commitSha, DesiredCheck desired) {
        jdbc.sql("""
                        UPDATE git_integration.check_runs
                           SET desired_state = :state, desired_conclusion = :conclusion,
                               desired_details_url = :detailsUrl, desired_summary = :summary, desired_phase = :phase,
                               desired_revision = desired_revision + 1, attempts = 0, next_attempt_at = now(),
                               updated_at = now()
                         WHERE org_id = :orgId AND app_id = :appId AND commit_sha = :commitSha
                        """)
                .param("orgId", orgId)
                .param("appId", appId)
                .param("commitSha", commitSha)
                .params(desiredParams(desired))
                .update();
    }

    /**
     * Leases up to {@code batchSize} due rows, oldest first, for {@code lease}, with the target each is reported to. A
     * row another instance is claiming is skipped, not waited for.
     */
    @CrossTenant("the reporter delivers every org's check runs; each is written back under its own org id")
    List<Claimed> claimDue(int batchSize, Duration lease) {
        return jdbc.sql("""
                        WITH claimed AS (
                            UPDATE git_integration.check_runs
                               SET next_attempt_at = now() + make_interval(secs => :leaseSeconds)
                             WHERE (app_id, commit_sha) IN (
                                   SELECT app_id, commit_sha FROM git_integration.check_runs
                                    WHERE reported_revision IS DISTINCT FROM desired_revision
                                      AND next_attempt_at <= now()
                                    ORDER BY next_attempt_at
                                    LIMIT :batchSize
                                      FOR UPDATE SKIP LOCKED)
                            RETURNING *)
                        SELECT c.*, i.installation_id AS target_installation_id, l.repo_id AS target_repo_id,
                               a.slug AS target_app_slug
                          FROM claimed c
                          LEFT JOIN git_integration.repo_links l
                            ON l.org_id = c.org_id AND l.app_id = c.app_id AND l.status = 'ACTIVE'
                          LEFT JOIN git_integration.apps a
                            ON a.org_id = c.org_id AND a.app_id = c.app_id AND a.status = 'ACTIVE'
                          LEFT JOIN git_integration.installations i
                            ON i.installation_id = l.installation_id AND i.status = 'ACTIVE'
                         ORDER BY c.next_attempt_at, c.app_id, c.commit_sha
                        """)
                .param("leaseSeconds", seconds(lease))
                .param("batchSize", batchSize)
                .query((rs, row) -> new Claimed(checkRun(rs), target(rs)))
                .list();
    }

    /**
     * Records that GitHub now shows {@code reported} as check run {@code checkRunId}. The row stops being pending only if
     * its desire is still the one at {@code revision}.
     */
    void recordReported(
            String orgId, UUID appId, String commitSha, int revision, long checkRunId, CheckState reported) {
        jdbc.sql("""
                        UPDATE git_integration.check_runs
                           SET check_run_id = :checkRunId, last_reported_state = :reported,
                               reported_revision = CASE WHEN desired_revision = :revision
                                                        THEN desired_revision ELSE reported_revision END,
                               attempts = CASE WHEN desired_revision = :revision THEN 0 ELSE attempts END,
                               updated_at = now()
                         WHERE org_id = :orgId AND app_id = :appId AND commit_sha = :commitSha
                        """)
                .param("orgId", orgId)
                .param("appId", appId)
                .param("commitSha", commitSha)
                .param("revision", revision)
                .param("checkRunId", checkRunId)
                .param("reported", reported.wireName())
                .update();
    }

    /** @return 1 if the attempt was recorded, 0 if a newer desire arrived since the claim */
    int retryLater(String orgId, UUID appId, String commitSha, int revision, Duration delay) {
        return jdbc.sql("""
                        UPDATE git_integration.check_runs
                           SET attempts = attempts + 1, next_attempt_at = now() + make_interval(secs => :delaySeconds),
                               updated_at = now()
                         WHERE org_id = :orgId AND app_id = :appId AND commit_sha = :commitSha
                           AND desired_revision = :revision
                        """)
                .param("orgId", orgId)
                .param("appId", appId)
                .param("commitSha", commitSha)
                .param("revision", revision)
                .param("delaySeconds", seconds(delay))
                .update();
    }

    /** GitHub's rate limit: wait for its reset without spending an attempt. */
    void waitForRateLimit(String orgId, UUID appId, String commitSha, int revision, Instant resetAt, Duration jitter) {
        jdbc.sql("""
                        UPDATE git_integration.check_runs
                           SET next_attempt_at = greatest(CAST(:resetAt AS timestamptz), now())
                                                 + make_interval(secs => :jitterSeconds),
                               updated_at = now()
                         WHERE org_id = :orgId AND app_id = :appId AND commit_sha = :commitSha
                           AND desired_revision = :revision
                        """)
                .param("orgId", orgId)
                .param("appId", appId)
                .param("commitSha", commitSha)
                .param("revision", revision)
                .param("resetAt", Timestamp.from(resetAt))
                .param("jitterSeconds", seconds(jitter))
                .update();
    }

    /**
     * Stops delivering the desire at {@code revision} without reporting it; a later desire makes the row pending again.
     *
     * @return 1 if abandoned, 0 if a newer desire arrived since the claim
     */
    int abandon(String orgId, UUID appId, String commitSha, int revision) {
        return jdbc.sql("""
                        UPDATE git_integration.check_runs
                           SET reported_revision = desired_revision, updated_at = now()
                         WHERE org_id = :orgId AND app_id = :appId AND commit_sha = :commitSha
                           AND desired_revision = :revision
                        """)
                .param("orgId", orgId)
                .param("appId", appId)
                .param("commitSha", commitSha)
                .param("revision", revision)
                .update();
    }

    private static Map<String, Object> desiredParams(DesiredCheck desired) {
        Map<String, Object> params = new HashMap<>();
        params.put("state", desired.state().wireName());
        params.put(
                "conclusion",
                desired.conclusion() == null ? null : desired.conclusion().wireName());
        params.put("detailsUrl", desired.detailsUrl());
        params.put("summary", desired.summary());
        params.put("phase", desired.phase().name());
        return params;
    }

    private static CheckRun checkRun(ResultSet rs) throws SQLException {
        String conclusion = rs.getString("desired_conclusion");
        String reported = rs.getString("last_reported_state");
        return new CheckRun(
                rs.getObject("app_id", UUID.class),
                rs.getString("commit_sha"),
                rs.getString("org_id"),
                rs.getObject("check_run_id", Long.class),
                new DesiredCheck(
                        CheckState.fromWireName(rs.getString("desired_state")),
                        conclusion == null ? null : Conclusion.fromWireName(conclusion),
                        rs.getString("desired_details_url"),
                        rs.getString("desired_summary"),
                        Phase.valueOf(rs.getString("desired_phase"))),
                rs.getInt("desired_revision"),
                reported == null ? null : CheckState.fromWireName(reported),
                rs.getObject("reported_revision", Integer.class),
                rs.getInt("attempts"));
    }

    private static Target target(ResultSet rs) throws SQLException {
        Long installationId = rs.getObject("target_installation_id", Long.class);
        Long repoId = rs.getObject("target_repo_id", Long.class);
        String slug = rs.getString("target_app_slug");
        return installationId == null || repoId == null || slug == null
                ? null
                : new Target(installationId, repoId, slug);
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000.0;
    }
}
