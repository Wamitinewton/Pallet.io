package io.pallet.orgteam.member;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.events.OrgInviteAccepted;
import io.pallet.common.events.OrgMembershipChanged;
import io.pallet.common.events.OrgProvisioned;
import io.pallet.common.events.Topics;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.invite.InviteAcceptanceService;
import io.pallet.orgteam.org.OrgDeletionService;
import io.pallet.orgteam.org.OrgService;
import io.pallet.orgteam.retention.RetentionSweeps;
import io.pallet.orgteam.security.AccessContext;
import io.pallet.orgteam.support.TopicProbe;
import io.pallet.orgteam.support.TopicProbe.Received;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
@TestPropertySource(properties = "pallet.outbox.enabled=true")
class MembershipStatePublisherIntegrationTest {

    @Autowired
    private OrgService orgService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private InviteAcceptanceService inviteAcceptance;

    @Autowired
    private OrgDeletionService orgDeletion;

    @Autowired
    private RetentionSweeps retentionSweeps;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private KafkaContainer kafka;

    private final List<String> orgIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        orgIds.forEach(orgId -> {
            jdbc.update("DELETE FROM org_team.invites WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.memberships WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.outbox_events WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.organizations WHERE org_id = ?", orgId);
        });
        orgIds.clear();
    }

    @Test
    void provisioningAPersonalOrgPublishesTheOwner() {
        String orgId = "org-" + UUID.randomUUID();
        orgIds.add(orgId);
        String owner = "user-" + UUID.randomUUID();

        try (TopicProbe probe = probe()) {
            transaction.executeWithoutResult(status -> orgService.provision(
                    OrgProvisioned.of(orgId, "Ada", "ada-" + orgId.substring(4, 12), owner, "ada@example.com", "Ada")));

            List<Received> records = probe.awaitMembershipCount(orgId, 1);
            assertThat(records).hasSize(1);
            assertState(records.getFirst(), orgId, owner, "owner", "ACTIVE", 0);
            assertThat(version(orgId, owner)).isZero();
        }
    }

    @Test
    void addRoleChangeAndRemovalPublishTheStateAfterEachCommitWithStrictlyIncreasingVersions() {
        try (TopicProbe probe = probe()) {
            Org org = teamOrg();
            String dev = accept(org, "DEVELOPER");

            memberService.changeRole(org.orgId(), org.owner(), dev, Role.ADMIN);
            long afterRoleChange = version(org.orgId(), dev);
            memberService.remove(org.orgId(), org.owner(), dev);
            long afterRemoval = version(org.orgId(), dev);

            List<Received> records = probe.awaitMembershipCount(org.orgId(), 4);
            assertThat(records).hasSize(4);
            List<Received> owner = forKey(records, org.orgId(), org.owner());
            assertThat(owner).hasSize(1);
            assertState(owner.getFirst(), org.orgId(), org.owner(), "owner", "ACTIVE", 0);
            List<Received> member = forKey(records, org.orgId(), dev);
            assertThat(member).hasSize(3);
            assertState(member.get(0), org.orgId(), dev, "developer", "ACTIVE", 0);
            assertState(member.get(1), org.orgId(), dev, "admin", "ACTIVE", afterRoleChange);
            assertState(member.get(2), org.orgId(), dev, "admin", "REMOVED", afterRemoval);
            assertThat(afterRoleChange).isPositive();
            assertThat(afterRemoval).isGreaterThan(afterRoleChange);
        }
    }

    @Test
    void ownershipTransferPublishesThePromotionThenTheDemotionEachWithItsOwnVersion() {
        try (TopicProbe probe = probe()) {
            Org org = teamOrg();
            String admin = accept(org, "ADMIN");

            memberService.transferOwnership(org.orgId(), org.owner(), admin);

            List<Received> records = probe.awaitMembershipCount(org.orgId(), 4);
            assertThat(records).hasSize(4);
            assertState(
                    forKey(records, org.orgId(), admin).getLast(),
                    org.orgId(),
                    admin,
                    "owner",
                    "ACTIVE",
                    version(org.orgId(), admin));
            assertState(
                    forKey(records, org.orgId(), org.owner()).getLast(),
                    org.orgId(),
                    org.owner(),
                    "admin",
                    "ACTIVE",
                    version(org.orgId(), org.owner()));
            assertThat(version(org.orgId(), org.owner())).isEqualTo(1);
            assertThat(version(org.orgId(), admin)).isEqualTo(1);
            assertThat(outboxKeys(org.orgId()).subList(2, 4))
                    .containsExactly(key(org.orgId(), admin), key(org.orgId(), org.owner()));
        }
    }

    @Test
    void deletingAThreeMemberOrgPublishesARemovedRecordPerMemberAboveItsLastVersion() {
        try (TopicProbe probe = probe()) {
            Org org = teamOrg();
            String dev = accept(org, "DEVELOPER");
            String viewer = accept(org, "VIEWER");

            orgDeletion.delete(org.orgId(), org.owner(), org.slug());

            List<Received> records = probe.awaitMembershipCount(org.orgId(), 6);
            assertThat(records).hasSize(6);
            for (String userId : List.of(org.owner(), dev, viewer)) {
                List<Received> history = forKey(records, org.orgId(), userId);
                assertThat(history).hasSize(2);
                assertThat(history.getFirst().body().get("status").asString()).isEqualTo("ACTIVE");
                JsonNode removal = history.getLast().body();
                assertThat(removal.get("status").asString()).isEqualTo(OrgMembershipChanged.STATUS_REMOVED);
                assertThat(removal.get("membershipVersion").asLong())
                        .isEqualTo(version(org.orgId(), userId))
                        .isGreaterThan(history.getFirst()
                                .body()
                                .get("membershipVersion")
                                .asLong());
            }
        }
    }

    @Test
    void aRolledBackRoleChangePublishesNothing() {
        Org org = teamOrg();
        String dev = accept(org, "DEVELOPER");
        int before = membershipRecords(org.orgId());

        transaction.executeWithoutResult(status -> {
            memberService.changeRole(org.orgId(), org.owner(), dev, Role.VIEWER);
            status.setRollbackOnly();
        });

        assertThat(membershipRecords(org.orgId())).isEqualTo(before);
        assertThat(version(org.orgId(), dev)).isZero();
    }

    @Test
    void sweepingARemovedMembershipPublishesATombstoneSoAReinviteStartsClean() {
        Org org = teamOrg();
        String dev = accept(org, "DEVELOPER");
        memberService.remove(org.orgId(), org.owner(), dev);
        jdbc.update(
                "UPDATE org_team.memberships SET removed_at = now() - interval '400 days' WHERE org_id = ? AND user_id = ?",
                org.orgId(),
                dev);

        try (TopicProbe probe = probe()) {
            retentionSweeps.sweepRemovedMemberships();

            List<Received> tombstones = probe.awaitMembershipCount(org.orgId(), 4).stream()
                    .filter(record -> record.body() == null)
                    .toList();
            assertThat(tombstones).extracting(Received::key).containsExactly(key(org.orgId(), dev));
        }
    }

    private record Org(String orgId, String slug, String owner) {}

    private Org teamOrg() {
        String owner = "user-" + UUID.randomUUID();
        String slug = "team-" + UUID.randomUUID().toString().substring(0, 8);
        AccessContext caller = new AccessContext(null, owner, null, Instant.now(), owner + "@example.com", "Owner");
        String orgId = orgService.createTeamOrg(caller, "Team " + slug, slug).orgId();
        orgIds.add(orgId);
        return new Org(orgId, slug, owner);
    }

    private String accept(Org org, String role) {
        String userId = "user-" + UUID.randomUUID();
        String email = userId + "@example.com";
        UUID inviteId = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO org_team.invites (id, org_id, email, role, invited_by_user_id, status, expires_at)
                        VALUES (?, ?, ?, ?, ?, 'PENDING', now() + interval '1 day')
                        """, inviteId, org.orgId(), email, role, org.owner());
        transaction.executeWithoutResult(status -> inviteAcceptance.handle(new OrgInviteAccepted(
                UUID.randomUUID(),
                OrgInviteAccepted.TYPE,
                org.orgId(),
                Instant.now(),
                inviteId.toString(),
                userId,
                email,
                "Invitee",
                role.toLowerCase())));
        return userId;
    }

    private static void assertState(
            Received record, String orgId, String userId, String role, String status, long version) {
        assertThat(record.topic()).isEqualTo(Topics.ORG_MEMBERSHIP_CHANGED);
        assertThat(record.key()).isEqualTo(key(orgId, userId));
        JsonNode body = record.body();
        assertThat(body.get("orgId").asString()).isEqualTo(orgId);
        assertThat(body.get("userId").asString()).isEqualTo(userId);
        assertThat(body.get("role").asString()).isEqualTo(role);
        assertThat(body.get("status").asString()).isEqualTo(status);
        assertThat(body.get("membershipVersion").asLong()).isEqualTo(version);
    }

    private static List<Received> forKey(List<Received> records, String orgId, String userId) {
        return records.stream()
                .filter(record -> record.key().equals(key(orgId, userId)))
                .toList();
    }

    private List<String> outboxKeys(String orgId) {
        return jdbc.queryForList(
                "SELECT record_key FROM org_team.outbox_events WHERE org_id = ? AND event_type = ? ORDER BY id",
                String.class,
                orgId,
                Topics.ORG_MEMBERSHIP_CHANGED);
    }

    private static String key(String orgId, String userId) {
        return OrgMembershipChanged.key(orgId, userId);
    }

    private long version(String orgId, String userId) {
        return jdbc.queryForObject(
                "SELECT version FROM org_team.memberships WHERE org_id = ? AND user_id = ?", Long.class, orgId, userId);
    }

    private int membershipRecords(String orgId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM org_team.outbox_events WHERE org_id = ? AND event_type = ?",
                Integer.class,
                orgId,
                Topics.ORG_MEMBERSHIP_CHANGED);
    }

    private TopicProbe probe() {
        return new TopicProbe(kafka.getBootstrapServers(), jsonMapper, Topics.ORG_MEMBERSHIP_CHANGED);
    }
}
