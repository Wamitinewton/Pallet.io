package io.pallet.buildqueue.dispatch;

import io.pallet.buildqueue.config.CrossTenant;
import io.pallet.buildqueue.dispatch.LimitProfiles.OverrideRow;
import io.pallet.buildqueue.dispatch.LimitProfiles.ProfileRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The dispatcher's reads. Each spells out the status predicates of the partial indexes it relies on, so the planner
 * reads {@code ix_builds_queued} and {@code ix_builds_in_flight} rather than every build ever kept.
 */
@Repository
class DispatchQuery {

    /** {@code traceparent} is the build request's trace, which its dispatch continues. */
    record Candidate(UUID buildId, String orgId, String traceparent) {}

    /** The candidate query's text and parameters; the text depends only on how many profiles and overrides exist. */
    record Statement(String sql, Map<String, Object> params) {}

    private static final String NO_OVERRIDES = "(NULL::text, NULL::text)";

    private static final String CANDIDATES = """
            WITH limits(profile, max_concurrent) AS (VALUES {profiles}),
                 overrides(org_id, profile) AS (VALUES {overrides}),
                 org_limits AS (
                     SELECT o.org_id, o.last_served_at, l.max_concurrent
                       FROM build_queue.orgs o
                       LEFT JOIN overrides ov ON ov.org_id = o.org_id
                       JOIN limits l ON l.profile = coalesce(ov.profile, :defaultProfile)
                      WHERE o.status = 'ACTIVE'),
                 in_flight AS (
                     SELECT org_id, count(*) AS n
                       FROM build_queue.builds
                      WHERE status IN ('DISPATCHED', 'RUNNING')
                      GROUP BY org_id),
                 eligible AS (
                     SELECT DISTINCT ON (b.org_id) b.build_id, b.org_id, b.traceparent, ol.last_served_at
                       FROM build_queue.builds b
                       JOIN org_limits ol ON ol.org_id = b.org_id
                       LEFT JOIN in_flight f ON f.org_id = b.org_id
                      WHERE b.status = 'QUEUED'
                        AND coalesce(f.n, 0) < ol.max_concurrent
                        AND NOT EXISTS (SELECT 1 FROM build_queue.builds x
                                         WHERE x.app_id = b.app_id AND x.status IN ('DISPATCHED', 'RUNNING'))
                      ORDER BY b.org_id, b.priority_class, b.enqueued_at, b.build_id)
            SELECT build_id, org_id, traceparent FROM eligible
             ORDER BY last_served_at NULLS FIRST, org_id
             LIMIT :limit
            """;

    private final JdbcClient jdbc;
    private final LimitProfiles limits;

    DispatchQuery(JdbcClient jdbc, LimitProfiles limits) {
        this.jdbc = jdbc;
        this.limits = limits;
    }

    /**
     * At most {@code limit} dispatchable builds, one per org: each org's first by priority class then arrival, among
     * builds whose app has nothing in flight and whose org is below its {@code maxConcurrent}. Orgs come least recently
     * served first, never-served ones before all others, ties broken by org id.
     */
    @CrossTenant("the dispatcher serves every org")
    List<Candidate> next(int limit) {
        Statement statement = candidates(limit);
        return jdbc.sql(statement.sql())
                .params(statement.params())
                .query((row, rowNumber) -> new Candidate(
                        row.getObject("build_id", UUID.class), row.getString("org_id"), row.getString("traceparent")))
                .list();
    }

    /** Builds holding a slot of the platform's {@code global-concurrency}. */
    @CrossTenant("platform-wide capacity spans every org")
    int countInFlight() {
        return jdbc.sql("SELECT count(*) FROM build_queue.builds WHERE status IN ('DISPATCHED', 'RUNNING')")
                .query(Integer.class)
                .single();
    }

    @CrossTenant("the dispatcher serves every org")
    Statement candidates(int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("defaultProfile", limits.defaultProfileName());
        params.put("limit", limit);

        List<String> profileRows = new ArrayList<>();
        List<ProfileRow> profiles = limits.asValuesRows();
        for (int i = 0; i < profiles.size(); i++) {
            profileRows.add("(CAST(:profile%d AS text), CAST(:maxConcurrent%d AS integer))".formatted(i, i));
            params.put("profile" + i, profiles.get(i).profile());
            params.put("maxConcurrent" + i, profiles.get(i).maxConcurrent());
        }

        List<String> overrideRows = new ArrayList<>();
        List<OverrideRow> overrides = limits.overrideRows();
        for (int i = 0; i < overrides.size(); i++) {
            overrideRows.add("(CAST(:overrideOrg%d AS text), CAST(:overrideProfile%d AS text))".formatted(i, i));
            params.put("overrideOrg" + i, overrides.get(i).orgId());
            params.put("overrideProfile" + i, overrides.get(i).profile());
        }

        String sql = CANDIDATES
                .replace("{profiles}", String.join(", ", profileRows))
                .replace("{overrides}", overrideRows.isEmpty() ? NO_OVERRIDES : String.join(", ", overrideRows));
        return new Statement(sql, params);
    }
}
