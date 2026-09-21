package io.pallet.orgteam.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import io.pallet.common.events.OrgInviteAccepted;
import io.pallet.common.events.OrgInviteRejected;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.outbox.TopicProbe;
import io.pallet.orgteam.outbox.TopicProbe.Received;
import io.pallet.orgteam.security.OrgFixtures;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
@TestPropertySource(properties = "pallet.orgteam.outbox.enabled=true")
class OrgInviteAcceptedListenerIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Duration QUIET = Duration.ofSeconds(3);

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private InviteService inviteService;

    @MockitoSpyBean
    private JsonMapper jsonMapper;

    private OrgFixtures fixtures;
    private final List<String> orgIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixtures = new OrgFixtures(jdbc);
    }

    @AfterEach
    void cleanUp() {
        orgIds.forEach(orgId -> {
            jdbc.update("DELETE FROM org_team.invites WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.outbox_events WHERE org_id = ?", orgId);
        });
        fixtures.cleanUp();
        orgIds.clear();
    }

    private String newOrg() {
        String orgId = fixtures.newActiveOrg();
        orgIds.add(orgId);
        return orgId;
    }

    private UUID insertInvite(String orgId, String email, String role, String status, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO org_team.invites (id, org_id, email, role, invited_by_user_id, status, expires_at)
                        VALUES (?, ?, ?, ?, 'inviter', ?, ?)
                        """, id, orgId, email, role, status, Timestamp.from(expiresAt));
        return id;
    }

    private static OrgInviteAccepted accepted(String orgId, UUID inviteId, String userId, String email, Instant at) {
        return new OrgInviteAccepted(
                UUID.randomUUID(),
                OrgInviteAccepted.TYPE,
                orgId,
                at,
                inviteId.toString(),
                userId,
                email,
                "Jane Doe",
                "owner");
    }

    private static String newUser() {
        return "user-" + UUID.randomUUID();
    }

    private static String newEmail() {
        return "invitee-" + UUID.randomUUID() + "@example.com";
    }

    private TopicProbe probe(String... topics) {
        return new TopicProbe(kafka.getBootstrapServers(), jsonMapper, topics);
    }

    private int memberCount(String orgId, String userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM org_team.memberships WHERE org_id = ? AND user_id = ?",
                Integer.class,
                orgId,
                userId);
    }

    private String inviteStatus(UUID inviteId) {
        return jdbc.queryForObject("SELECT status FROM org_team.invites WHERE id = ?", String.class, inviteId);
    }

    private int outboxCount(String orgId, String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM org_team.outbox_events WHERE org_id = ? AND event_type = ?",
                Integer.class,
                orgId,
                eventType);
    }

    private void awaitProcessed(OrgInviteAccepted event) {
        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM org_team.processed_events WHERE event_id = ?",
                                Integer.class,
                                event.eventId()))
                        .isEqualTo(1));
    }

    private void assertRejectedOnce(String orgId, UUID inviteId, String userId, String email, String reason) {
        try (TopicProbe probe = probe(Topics.ORG_INVITE_REJECTED)) {
            List<Received> rejected = probe.awaitCount(orgId, 1);
            assertThat(rejected).hasSize(1);
            Received body = rejected.getFirst();
            assertThat(body.body().get("reason").asString()).isEqualTo(reason);
            assertThat(body.body().get("inviteId").asString()).isEqualTo(inviteId.toString());
            assertThat(body.body().get("userId").asString()).isEqualTo(userId);
            assertThat(body.body().get("email").asString()).isEqualTo(email);
        }
        assertThat(outboxCount(orgId, OrgInviteRejected.TYPE)).isEqualTo(1);
        assertThat(memberCount(orgId, userId)).isZero();
    }

    @Test
    void aValidAcceptCreatesTheMembershipWithTheInvitesRoleAndRelaysOneMemberAdded() {
        String orgId = newOrg();
        String email = newEmail();
        String userId = newUser();
        UUID inviteId =
                insertInvite(orgId, email, "DEVELOPER", "PENDING", Instant.now().plus(Duration.ofHours(1)));
        OrgInviteAccepted event = accepted(orgId, inviteId, userId, "  " + email.toUpperCase() + " ", Instant.now());

        try (TopicProbe probe = probe(Topics.ORG_MEMBER_ADDED)) {
            publisher.publish(event);
            List<Received> added = probe.awaitCount(orgId, 1);

            assertThat(added.getFirst().body().get("userId").asString()).isEqualTo(userId);
            assertThat(added.getFirst().body().get("email").asString()).isEqualTo(email);
        }

        Map<String, Object> member = jdbc.queryForMap(
                "SELECT email, display_name, role, status, profile_synced_at FROM org_team.memberships"
                        + " WHERE org_id = ? AND user_id = ?",
                orgId,
                userId);
        assertThat(member)
                .containsEntry("email", email)
                .containsEntry("display_name", "Jane Doe")
                .containsEntry("role", "DEVELOPER")
                .containsEntry("status", "ACTIVE");
        assertThat(member.get("profile_synced_at")).isNotNull();
        assertThat(inviteStatus(inviteId)).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject(
                        "SELECT responded_at IS NOT NULL FROM org_team.invites WHERE id = ?", Boolean.class, inviteId))
                .isTrue();
        assertThat(jdbc.queryForList(
                        "SELECT payload->>'action' FROM org_team.outbox_events"
                                + " WHERE org_id = ? AND event_type = 'audit.event.recorded' ORDER BY id",
                        String.class,
                        orgId))
                .containsExactly("invite.accepted", "member.added");
    }

    @Test
    void redeliveringTheEventCreatesNoDuplicateMembershipAndNoSecondMemberAdded() {
        String orgId = newOrg();
        String email = newEmail();
        String userId = newUser();
        UUID inviteId =
                insertInvite(orgId, email, "VIEWER", "PENDING", Instant.now().plus(Duration.ofHours(1)));
        OrgInviteAccepted event = accepted(orgId, inviteId, userId, email, Instant.now());

        try (TopicProbe probe = probe(Topics.ORG_MEMBER_ADDED)) {
            publisher.publish(event);
            probe.awaitCount(orgId, 1);
            publisher.publish(event);
            OrgInviteAccepted underAnotherEventId = accepted(orgId, inviteId, userId, email, Instant.now());
            publisher.publish(underAnotherEventId);
            awaitProcessed(underAnotherEventId);

            assertThat(probe.observe(orgId, QUIET)).hasSize(1);
        }
        assertThat(memberCount(orgId, userId)).isEqualTo(1);
        assertThat(outboxCount(orgId, "org.member.added")).isEqualTo(1);
        assertThat(outboxCount(orgId, OrgInviteRejected.TYPE)).isZero();
    }

    @Test
    void anAcceptOfARevokedInviteIsRejectedExactlyOnceWithNoMembership() {
        String orgId = newOrg();
        String email = newEmail();
        String userId = newUser();
        UUID inviteId =
                insertInvite(orgId, email, "VIEWER", "REVOKED", Instant.now().plus(Duration.ofHours(1)));

        publisher.publish(accepted(orgId, inviteId, userId, email, Instant.now()));

        assertRejectedOnce(orgId, inviteId, userId, email, OrgInviteRejected.REASON_REVOKED);
        assertThat(inviteStatus(inviteId)).isEqualTo("REVOKED");
    }

    @Test
    void anAcceptAfterExpiryPlusGraceIsRejectedAsExpired() {
        String orgId = newOrg();
        String email = newEmail();
        String userId = newUser();
        UUID inviteId =
                insertInvite(orgId, email, "VIEWER", "PENDING", Instant.now().minus(Duration.ofMinutes(10)));

        publisher.publish(accepted(orgId, inviteId, userId, email, Instant.now()));

        assertRejectedOnce(orgId, inviteId, userId, email, OrgInviteRejected.REASON_EXPIRED);
    }

    @Test
    void anAcceptMadeBeforeExpiryIsHonouredEvenThoughProcessedAfterIt() {
        String orgId = newOrg();
        String email = newEmail();
        String userId = newUser();
        Instant expiresAt = Instant.now().minus(Duration.ofSeconds(30));
        UUID inviteId = insertInvite(orgId, email, "VIEWER", "PENDING", expiresAt);

        try (TopicProbe probe = probe(Topics.ORG_MEMBER_ADDED)) {
            publisher.publish(accepted(orgId, inviteId, userId, email, expiresAt.minusSeconds(30)));
            probe.awaitCount(orgId, 1);
        }
        assertThat(memberCount(orgId, userId)).isEqualTo(1);
        assertThat(inviteStatus(inviteId)).isEqualTo("ACCEPTED");
        assertThat(outboxCount(orgId, OrgInviteRejected.TYPE)).isZero();
    }

    @Test
    void anUnknownInviteIdIsRejectedAsUnknown() {
        String orgId = newOrg();
        String userId = newUser();
        UUID unknown = UUID.randomUUID();
        String email = newEmail();

        publisher.publish(accepted(orgId, unknown, userId, email, Instant.now()));

        assertRejectedOnce(orgId, unknown, userId, email, OrgInviteRejected.REASON_UNKNOWN_INVITE);
    }

    @Test
    void anotherOrgsInviteIsNeverHonoured() {
        String orgId = newOrg();
        String otherOrgId = newOrg();
        String email = newEmail();
        String userId = newUser();
        UUID othersInvite = insertInvite(
                otherOrgId, email, "ADMIN", "PENDING", Instant.now().plus(Duration.ofHours(1)));

        publisher.publish(accepted(orgId, othersInvite, userId, email, Instant.now()));

        assertRejectedOnce(orgId, othersInvite, userId, email, OrgInviteRejected.REASON_UNKNOWN_INVITE);
        assertThat(memberCount(otherOrgId, userId)).isZero();
        assertThat(inviteStatus(othersInvite)).isEqualTo("PENDING");
    }

    @Test
    void anAcceptForADeletedOrganizationIsRejectedAsOrgDeleted() {
        String orgId = newOrg();
        String email = newEmail();
        String userId = newUser();
        UUID inviteId =
                insertInvite(orgId, email, "VIEWER", "REVOKED", Instant.now().plus(Duration.ofHours(1)));
        jdbc.update("UPDATE org_team.organizations SET status = 'DELETED' WHERE org_id = ?", orgId);

        publisher.publish(accepted(orgId, inviteId, userId, email, Instant.now()));

        assertRejectedOnce(orgId, inviteId, userId, email, OrgInviteRejected.REASON_ORG_DELETED);
    }

    @Test
    void anAcceptForAnOrganizationThatNeverExistedIsRejectedAsUnknown() {
        String orgId = "org-" + UUID.randomUUID();
        orgIds.add(orgId);
        UUID inviteId = UUID.randomUUID();
        String userId = newUser();
        String email = newEmail();

        publisher.publish(accepted(orgId, inviteId, userId, email, Instant.now()));

        assertRejectedOnce(orgId, inviteId, userId, email, OrgInviteRejected.REASON_UNKNOWN_INVITE);
    }

    @Test
    void theEventsRoleClaimCannotEscalateTheInvitesRole() {
        String orgId = newOrg();
        String email = newEmail();
        String userId = newUser();
        UUID inviteId =
                insertInvite(orgId, email, "VIEWER", "PENDING", Instant.now().plus(Duration.ofHours(1)));
        OrgInviteAccepted event = accepted(orgId, inviteId, userId, email, Instant.now());

        try (TopicProbe probe = probe(Topics.ORG_MEMBER_ADDED)) {
            publisher.publish(event);
            probe.awaitCount(orgId, 1);
        }

        assertThat(jdbc.queryForObject(
                        "SELECT role FROM org_team.memberships WHERE org_id = ? AND user_id = ?",
                        String.class,
                        orgId,
                        userId))
                .isEqualTo("VIEWER");
    }

    @Test
    void anEmailHeldByAnotherActiveMemberIsDeadLetteredAndNothingChanges() {
        String orgId = newOrg();
        String email = newEmail();
        UUID inviteId =
                insertInvite(orgId, email, "VIEWER", "PENDING", Instant.now().plus(Duration.ofHours(1)));
        jdbc.update(
                "INSERT INTO org_team.memberships (org_id, user_id, email, display_name, role, status)"
                        + " VALUES (?, 'holder', ?, 'Holder', 'DEVELOPER', 'ACTIVE')",
                orgId,
                email);
        String userId = newUser();

        try (TopicProbe probe = probe(Topics.deadLetter(Topics.ORG_INVITE_ACCEPTED))) {
            publisher.publish(accepted(orgId, inviteId, userId, email, Instant.now()));

            assertThat(probe.awaitCount(orgId, 1)).hasSize(1);
        }
        assertThat(memberCount(orgId, userId)).isZero();
        assertThat(inviteStatus(inviteId)).isEqualTo("PENDING");
        assertThat(outboxCount(orgId, OrgInviteRejected.TYPE)).isZero();
    }

    @Test
    void aMalformedEventIsDeadLetteredWithNothingPersisted() {
        String orgId = newOrg();
        String userId = newUser();
        OrgInviteAccepted event = new OrgInviteAccepted(
                UUID.randomUUID(),
                OrgInviteAccepted.TYPE,
                orgId,
                Instant.now(),
                "not-a-uuid",
                userId,
                newEmail(),
                "Jane",
                "viewer");

        try (TopicProbe probe = probe(Topics.deadLetter(Topics.ORG_INVITE_ACCEPTED))) {
            publisher.publish(event);

            assertThat(probe.awaitCount(orgId, 1)).hasSize(1);
        }
        assertThat(memberCount(orgId, userId)).isZero();
        assertThat(outboxCount(orgId, OrgInviteRejected.TYPE)).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.processed_events WHERE event_id = ?",
                        Integer.class,
                        event.eventId()))
                .isZero();
    }

    @Test
    void aCrashMidHandlerRollsBackTheInboxClaimAndTheRedeliverySucceeds() {
        String orgId = newOrg();
        String email = newEmail();
        String userId = newUser();
        UUID inviteId =
                insertInvite(orgId, email, "VIEWER", "PENDING", Instant.now().plus(Duration.ofHours(1)));
        OrgInviteAccepted event = accepted(orgId, inviteId, userId, email, Instant.now());
        doThrow(new IllegalStateException("crash before commit"))
                .doCallRealMethod()
                .when(jsonMapper)
                .writeValueAsString(any());

        publisher.publish(event);

        awaitProcessed(event);
        assertThat(memberCount(orgId, userId)).isEqualTo(1);
        assertThat(inviteStatus(inviteId)).isEqualTo("ACCEPTED");
        assertThat(outboxCount(orgId, "org.member.added")).isEqualTo(1);
    }

    @Test
    void anAcceptRacingARevokeNeverLeavesBothAMembershipAndARevokedInvite() throws Exception {
        for (int round = 0; round < 5; round++) {
            String orgId = newOrg();
            String owner = fixtures.newMember(orgId, "OWNER", "ACTIVE");
            String email = newEmail();
            String userId = newUser();
            UUID inviteId = insertInvite(
                    orgId, email, "VIEWER", "PENDING", Instant.now().plus(Duration.ofHours(1)));
            OrgInviteAccepted event = accepted(orgId, inviteId, userId, email, Instant.now());
            CountDownLatch start = new CountDownLatch(1);

            CompletableFuture<Boolean> revoke = CompletableFuture.supplyAsync(() -> {
                awaitStart(start);
                try {
                    inviteService.revoke(orgId, owner, inviteId);
                    return true;
                } catch (InviteExceptions.InviteNotFoundException accepted) {
                    return false;
                }
            });
            CompletableFuture<Void> accept = CompletableFuture.runAsync(() -> {
                awaitStart(start);
                publisher.publish(event);
            });
            start.countDown();
            accept.get();
            boolean revoked = revoke.get();
            awaitProcessed(event);

            if (revoked) {
                assertThat(inviteStatus(inviteId)).isEqualTo("REVOKED");
                assertThat(memberCount(orgId, userId)).isZero();
                assertThat(outboxCount(orgId, OrgInviteRejected.TYPE)).isEqualTo(1);
            } else {
                assertThat(inviteStatus(inviteId)).isEqualTo("ACCEPTED");
                assertThat(memberCount(orgId, userId)).isEqualTo(1);
                assertThat(outboxCount(orgId, OrgInviteRejected.TYPE)).isZero();
            }
        }
    }

    private static void awaitStart(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
