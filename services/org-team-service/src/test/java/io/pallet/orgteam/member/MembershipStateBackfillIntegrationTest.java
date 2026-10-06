package io.pallet.orgteam.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import io.pallet.common.events.Topics;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.security.OrgFixtures;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
@TestPropertySource(properties = "pallet.orgteam.membership-backfill.batch-size=2")
class MembershipStateBackfillIntegrationTest {

    @Autowired
    private MembershipStateBackfill backfill;

    @Autowired
    private MemberService memberService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private MembershipStatePublisher publisher;

    private OrgFixtures fixtures;
    private long outboxHighWater;

    @BeforeEach
    void setUp() {
        fixtures = new OrgFixtures(jdbc);
        // The run every boot starts must finish before the cursor is reset, or the two would race.
        await().atMost(Duration.ofSeconds(30))
                .until(() -> jdbc.queryForObject(
                        "SELECT completed_at IS NOT NULL FROM org_team.membership_backfill_cursor", Boolean.class));
        jdbc.update("""
                UPDATE org_team.membership_backfill_cursor
                SET last_org_id = '', last_user_id = '', published = 0, completed_at = NULL
                """);
        outboxHighWater = jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM org_team.outbox_events", Long.class);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM org_team.outbox_events WHERE id > ?", outboxHighWater);
        fixtures.cleanUp();
    }

    @Test
    void publishesEveryMembershipOnceWithItsCurrentVersionAndThenDisablesItself() {
        String active = fixtures.newActiveOrg();
        String owner = fixtures.newMember(active, "OWNER", "ACTIVE");
        String removed = fixtures.newMember(active, "VIEWER", "REMOVED");
        jdbc.update("UPDATE org_team.memberships SET version = 7 WHERE org_id = ? AND user_id = ?", active, removed);
        String deleted = fixtures.newOrg("DELETED");
        String formerOwner = fixtures.newMember(deleted, "OWNER", "ACTIVE");

        backfill.run();

        assertThat(record(active, owner)).containsEntry("role", "owner").containsEntry("status", "ACTIVE");
        assertThat(record(active, owner).get("version")).isEqualTo(0L);
        assertThat(record(active, removed)).containsEntry("role", "viewer").containsEntry("status", "REMOVED");
        assertThat(record(active, removed).get("version")).isEqualTo(7L);
        assertThat(record(deleted, formerOwner)).containsEntry("status", "REMOVED");
        assertThat(backfilledKeys()).containsExactlyInAnyOrderElementsOf(allMembershipKeys());

        assertThat(backfill.run()).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT completed_at IS NOT NULL FROM org_team.membership_backfill_cursor", Boolean.class))
                .isTrue();
    }

    @Test
    void aRunThatDiesHalfwayResumesFromItsCursorWithoutSkippingOrRepeatingAny() {
        String orgId = fixtures.newActiveOrg();
        for (int i = 0; i < 5; i++) {
            fixtures.newMember(orgId, "DEVELOPER", "ACTIVE");
        }
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
                    if (calls.incrementAndGet() == 5) {
                        throw new IllegalStateException("killed mid-batch");
                    }
                    return invocation.callRealMethod();
                })
                .when(publisherTarget())
                .publish(any(MembershipState.class));

        assertThatThrownBy(backfill::run).hasMessage("killed mid-batch");
        assertThat(backfilledKeys()).hasSize(4);

        reset(publisherTarget());
        backfill.run();

        assertThat(backfilledKeys()).containsExactlyInAnyOrderElementsOf(allMembershipKeys());
    }

    @Test
    void aRoleChangeRacingTheBackfillIsRelayedAfterItWithTheHigherVersion() throws Exception {
        String orgId = fixtures.newActiveOrg();
        String owner = fixtures.newMember(orgId, "OWNER", "ACTIVE");
        jdbc.update("UPDATE org_team.organizations SET owner_user_id = ? WHERE org_id = ?", owner, orgId);
        String dev = fixtures.newMember(orgId, "DEVELOPER", "ACTIVE");
        CompletableFuture<MemberDto> roleChange = new CompletableFuture<>();
        doAnswer(invocation -> {
                    MembershipState state = invocation.getArgument(0);
                    if (state.userId().equals(dev) && !roleChange.isDone()) {
                        Thread.ofVirtual().start(() -> {
                            try {
                                roleChange.complete(memberService.changeRole(orgId, owner, dev, Role.ADMIN));
                            } catch (RuntimeException e) {
                                roleChange.completeExceptionally(e);
                            }
                        });
                        awaitBlockedOnRowLock(roleChange);
                    }
                    return invocation.callRealMethod();
                })
                .when(publisherTarget())
                .publish(any(MembershipState.class));

        backfill.run();
        roleChange.get(10, TimeUnit.SECONDS);

        List<Map<String, Object>> history = jdbc.queryForList("""
                SELECT payload->>'role' AS role, (payload->>'membershipVersion')::bigint AS version
                FROM org_team.outbox_events WHERE record_key = ? AND event_type = ? ORDER BY id
                """, orgId + ":" + dev, Topics.ORG_MEMBERSHIP_CHANGED);
        assertThat(history).hasSize(2);
        assertThat(history.get(0)).containsEntry("role", "developer").containsEntry("version", 0L);
        assertThat(history.get(1)).containsEntry("role", "admin").containsEntry("version", 1L);
    }

    // The spy sits behind the transactional proxy, so stubbing through the proxy would hit MANDATORY first.
    private MembershipStatePublisher publisherTarget() {
        return AopTestUtils.getUltimateTargetObject(publisher);
    }

    private void awaitBlockedOnRowLock(CompletableFuture<MemberDto> roleChange) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
                    Integer.class);
            if (waiting > 0) {
                return;
            }
            if (roleChange.isDone()) {
                roleChange.get();
                throw new AssertionError("the role change finished while the backfill held the row");
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the role change never blocked on the backfill's row lock");
    }

    private Map<String, Object> record(String orgId, String userId) {
        return jdbc.queryForMap("""
                SELECT payload->>'role' AS role, payload->>'status' AS status,
                       (payload->>'membershipVersion')::bigint AS version
                FROM org_team.outbox_events WHERE id > ? AND record_key = ? AND event_type = ?
                """, outboxHighWater, orgId + ":" + userId, Topics.ORG_MEMBERSHIP_CHANGED);
    }

    private List<String> backfilledKeys() {
        return jdbc.queryForList(
                "SELECT record_key FROM org_team.outbox_events WHERE id > ? AND event_type = ?",
                String.class,
                outboxHighWater,
                Topics.ORG_MEMBERSHIP_CHANGED);
    }

    private List<String> allMembershipKeys() {
        return jdbc.queryForList("SELECT org_id || ':' || user_id FROM org_team.memberships", String.class);
    }
}
