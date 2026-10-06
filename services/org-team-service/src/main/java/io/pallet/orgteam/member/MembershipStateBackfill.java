package io.pallet.orgteam.member;

import io.pallet.orgteam.config.OrgTeamProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes the current state of every membership that existed before {@code org.membership.changed} did. Starts on
 * every boot and resumes from its cursor after a crash; once the cursor is complete, a start costs one query.
 */
@Component
public class MembershipStateBackfill {

    /** Sits after the retention sweeps' keys in {@code SweepLock}; the relay's is {@code pallet.outbox.advisory-lock-key}. */
    static final long LOCK_KEY = 7305121507L;

    private static final Logger log = LoggerFactory.getLogger(MembershipStateBackfill.class);

    private record Cursor(String lastOrgId, String lastUserId, long published, boolean completed) {}

    private final JdbcClient jdbc;
    private final MembershipStatePublisher membershipState;
    private final TransactionTemplate transaction;
    private final OrgTeamProperties.MembershipBackfill settings;

    MembershipStateBackfill(
            JdbcClient jdbc,
            MembershipStatePublisher membershipState,
            PlatformTransactionManager transactionManager,
            OrgTeamProperties properties) {
        this.jdbc = jdbc;
        this.membershipState = membershipState;
        this.transaction = new TransactionTemplate(transactionManager);
        this.settings = properties.membershipBackfill();
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        Thread.ofVirtual().name("membership-state-backfill").start(() -> {
            try {
                run();
            } catch (RuntimeException e) {
                log.error("Membership state backfill failed; restart to resume from its cursor", e);
            }
        });
    }

    /**
     * Appends one batch per transaction until every membership is published or another instance holds the lock.
     *
     * @return records appended by this run
     */
    public long run() {
        long appended = 0;
        while (true) {
            Integer batch = transaction.execute(status -> nextBatch());
            if (batch == null) {
                break;
            }
            appended += batch;
            if (batch < settings.batchSize()) {
                Long total = jdbc.sql("SELECT published FROM org_team.membership_backfill_cursor WHERE id = 1")
                        .query(Long.class)
                        .single();
                log.info("Membership state backfill complete: {} records this run, {} in total", appended, total);
                break;
            }
        }
        return appended;
    }

    private Integer nextBatch() {
        boolean locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)")
                .param("key", LOCK_KEY)
                .query(Boolean.class)
                .single();
        if (!locked) {
            log.info("Membership state backfill is running on another instance");
            return null;
        }
        Cursor cursor = jdbc.sql("""
                        SELECT last_org_id, last_user_id, published, completed_at IS NOT NULL AS completed
                        FROM org_team.membership_backfill_cursor WHERE id = 1 FOR UPDATE
                        """)
                .query((rs, row) -> new Cursor(
                        rs.getString("last_org_id"),
                        rs.getString("last_user_id"),
                        rs.getLong("published"),
                        rs.getBoolean("completed")))
                .single();
        if (cursor.completed()) {
            return null;
        }

        // FOR SHARE holds off a concurrent membership write until this batch commits, so its own record (with a
        // higher version) is relayed after the one appended here.
        List<MembershipState> page = jdbc.sql("""
                        SELECT m.org_id, m.user_id, m.role, m.status, m.version, o.status AS org_status
                        FROM org_team.memberships m
                        JOIN org_team.organizations o ON o.org_id = m.org_id
                        WHERE (m.org_id, m.user_id) > (:lastOrgId, :lastUserId)
                        ORDER BY m.org_id, m.user_id
                        LIMIT :batchSize
                        FOR SHARE OF m
                        """)
                .param("lastOrgId", cursor.lastOrgId())
                .param("lastUserId", cursor.lastUserId())
                .param("batchSize", settings.batchSize())
                .query((rs, row) -> new MembershipState(
                        rs.getString("org_id"),
                        rs.getString("user_id"),
                        Role.valueOf(rs.getString("role")),
                        "DELETED".equals(rs.getString("org_status"))
                                ? MembershipStatus.REMOVED
                                : MembershipStatus.valueOf(rs.getString("status")),
                        rs.getLong("version")))
                .list();
        page.forEach(membershipState::publish);

        MembershipState last = page.isEmpty() ? null : page.getLast();
        jdbc.sql("""
                        UPDATE org_team.membership_backfill_cursor
                        SET last_org_id = :lastOrgId, last_user_id = :lastUserId, published = published + :count,
                            completed_at = CASE WHEN :count < :batchSize THEN now() END
                        WHERE id = 1
                        """)
                .param("lastOrgId", last == null ? cursor.lastOrgId() : last.orgId())
                .param("lastUserId", last == null ? cursor.lastUserId() : last.userId())
                .param("count", page.size())
                .param("batchSize", settings.batchSize())
                .update();
        return page.size();
    }
}
