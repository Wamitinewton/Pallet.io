package io.pallet.orgteam.retention;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.security.OrgFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class RetentionSweepsIntegrationTest {

    private static final int BATCH_SIZE = 10;

    @Autowired
    private RetentionSweeps sweeps;

    @Autowired
    private JdbcTemplate jdbc;

    private OrgFixtures fixtures;
    private String marker;

    @BeforeEach
    void setUp() {
        fixtures = new OrgFixtures(jdbc);
        marker = "sweep-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM org_team.outbox_events WHERE org_id = ?", marker);
        jdbc.update("DELETE FROM org_team.processed_events WHERE consumer = ?", marker);
        jdbc.update("DELETE FROM org_team.invites WHERE invited_by_user_id = ?", marker);
        fixtures.cleanUp();
    }

    @Test
    void publishedOutboxRowsPastTheWindowAreDeletedAndOpenRowsNeverAre() {
        UUID old = outbox("PUBLISHED", 8);
        UUID recent = outbox("PUBLISHED", 6);
        UUID pending = outbox("PENDING", 30);
        UUID parked = outbox("PARKED", 30);

        sweeps.sweepPublishedOutbox();

        assertThat(outboxExists(old)).isFalse();
        assertThat(outboxExists(recent)).isTrue();
        assertThat(outboxExists(pending)).isTrue();
        assertThat(outboxExists(parked)).isTrue();
    }

    @Test
    void processedEventsPastTheWindowAreDeleted() {
        UUID old = processed(15);
        UUID recent = processed(13);

        sweeps.sweepProcessedEvents();

        assertThat(processedExists(old)).isFalse();
        assertThat(processedExists(recent)).isTrue();
    }

    @Test
    void terminalInvitesPastTheWindowAreDeletedButPendingAndRecentOnesAreKept() {
        String orgId = fixtures.newActiveOrg();
        UUID accepted = invite(orgId, "a@example.com", "ACCEPTED", 91);
        UUID revoked = invite(orgId, "b@example.com", "REVOKED", 91);
        UUID expired = invite(orgId, "c@example.com", "EXPIRED", 91);
        UUID recent = invite(orgId, "d@example.com", "REVOKED", 89);
        UUID pending = invite(orgId, "e@example.com", "PENDING", null);

        sweeps.sweepTerminalInvites();

        assertThat(inviteExists(accepted)).isFalse();
        assertThat(inviteExists(revoked)).isFalse();
        assertThat(inviteExists(expired)).isFalse();
        assertThat(inviteExists(recent)).isTrue();
        assertThat(inviteExists(pending)).isTrue();
        jdbc.update("DELETE FROM org_team.invites WHERE org_id = ?", orgId);
    }

    @Test
    void removedMembershipsPastTheWindowAreDeletedButActiveAndRecentOnesAreKept() {
        String orgId = fixtures.newActiveOrg();
        String longRemoved = fixtures.newMember(orgId, "VIEWER", "REMOVED");
        String recentlyRemoved = fixtures.newMember(orgId, "VIEWER", "REMOVED");
        String active = fixtures.newMember(orgId, "VIEWER", "ACTIVE");
        backdateRemoval(orgId, longRemoved, 366);
        backdateRemoval(orgId, recentlyRemoved, 364);

        sweeps.sweepRemovedMemberships();

        assertThat(memberExists(orgId, longRemoved)).isFalse();
        assertThat(memberExists(orgId, recentlyRemoved)).isTrue();
        assertThat(memberExists(orgId, active)).isTrue();
    }

    @Test
    void aSweepLargerThanOneBatchLoopsUntilEverythingPastTheWindowIsGone() {
        List<UUID> old = new ArrayList<>();
        for (int i = 0; i < BATCH_SIZE * 3 + 5; i++) {
            old.add(outbox("PUBLISHED", 10));
        }
        UUID recent = outbox("PUBLISHED", 1);

        long deleted = sweeps.sweepPublishedOutbox();

        assertThat(deleted).isGreaterThanOrEqualTo(old.size());
        assertThat(old).noneMatch(this::outboxExists);
        assertThat(outboxExists(recent)).isTrue();
    }

    @Test
    void twoConcurrentSweepsDeleteEachRowExactlyOnceWithoutDeadlock() throws Exception {
        int seeded = BATCH_SIZE * 6;
        for (int i = 0; i < seeded; i++) {
            outbox("PUBLISHED", 10);
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Long> sweep = () -> {
            start.await();
            return sweeps.sweepPublishedOutbox();
        };
        try {
            Future<Long> first = pool.submit(sweep);
            Future<Long> second = pool.submit(sweep);
            start.countDown();

            long total = first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS);

            assertThat(total).isGreaterThanOrEqualTo(seeded);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM org_team.outbox_events WHERE org_id = ?", Integer.class, marker))
                    .isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    private UUID outbox(String status, int ageDays) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO org_team.outbox_events (event_id, org_id, event_type, payload, status, published_at)
                        VALUES (?, ?, 'org.member.added', '{}'::jsonb, ?,
                                CASE WHEN ? = 'PUBLISHED' THEN now() - make_interval(days => ?) END)
                        """, eventId, marker, status, status, ageDays);
        return eventId;
    }

    private boolean outboxExists(UUID eventId) {
        return exists("SELECT count(*) FROM org_team.outbox_events WHERE event_id = ?", eventId);
    }

    private UUID processed(int ageDays) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO org_team.processed_events (event_id, consumer, processed_at)
                        VALUES (?, ?, now() - make_interval(days => ?))
                        """, eventId, marker, ageDays);
        return eventId;
    }

    private boolean processedExists(UUID eventId) {
        return exists("SELECT count(*) FROM org_team.processed_events WHERE event_id = ?", eventId);
    }

    private UUID invite(String orgId, String email, String status, Integer respondedDaysAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO org_team.invites
                            (id, org_id, email, role, invited_by_user_id, status, expires_at, responded_at)
                        VALUES (?, ?, ?, 'VIEWER', ?, ?, now() + interval '1 day',
                                CASE WHEN ?::int IS NULL THEN NULL ELSE now() - make_interval(days => ?::int) END)
                        """, id, orgId, email, marker, status, respondedDaysAgo, respondedDaysAgo);
        return id;
    }

    private boolean inviteExists(UUID id) {
        return exists("SELECT count(*) FROM org_team.invites WHERE id = ?", id);
    }

    private void backdateRemoval(String orgId, String userId, int ageDays) {
        jdbc.update("""
                        UPDATE org_team.memberships SET removed_at = now() - make_interval(days => ?)
                        WHERE org_id = ? AND user_id = ?
                        """, ageDays, orgId, userId);
    }

    private boolean memberExists(String orgId, String userId) {
        return jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.memberships WHERE org_id = ? AND user_id = ?",
                        Integer.class,
                        orgId,
                        userId)
                == 1;
    }

    private boolean exists(String sql, UUID id) {
        return jdbc.queryForObject(sql, Integer.class, id) == 1;
    }
}
