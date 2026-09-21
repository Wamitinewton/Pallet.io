package io.pallet.orgteam.invite;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.events.OrgInviteAccepted;
import io.pallet.common.events.OrgInviteRejected;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.member.Role;
import io.pallet.orgteam.security.OrgFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class InviteExpirySweepIntegrationTest {

    @Autowired
    private InviteExpirySweep sweep;

    @Autowired
    private InviteService inviteService;

    @Autowired
    private InviteAcceptanceService acceptance;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    private OrgFixtures fixtures;
    private final List<String> orgIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixtures = new OrgFixtures(jdbc);
    }

    @AfterEach
    void tearDown() {
        orgIds.forEach(orgId -> {
            jdbc.update("DELETE FROM org_team.invites WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.outbox_events WHERE org_id = ?", orgId);
        });
        fixtures.cleanUp();
        orgIds.clear();
    }

    @Test
    void expiredPendingInvitesBecomeExpiredAndEverythingElseIsUntouched() {
        String orgId = newOrg();
        UUID expired = insert(orgId, "expired@example.com", "PENDING", "now() - interval '1 minute'");
        UUID live = insert(orgId, "live@example.com", "PENDING", "now() + interval '1 day'");
        UUID revoked = insert(orgId, "revoked@example.com", "REVOKED", "now() - interval '1 day'");
        UUID accepted = insert(orgId, "accepted@example.com", "ACCEPTED", "now() - interval '1 day'");

        assertThat(sweep.expirePending()).isGreaterThanOrEqualTo(1);

        assertThat(statusOf(expired)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject(
                        "SELECT responded_at IS NOT NULL FROM org_team.invites WHERE id = ?", Boolean.class, expired))
                .isTrue();
        assertThat(statusOf(live)).isEqualTo("PENDING");
        assertThat(statusOf(revoked)).isEqualTo("REVOKED");
        assertThat(statusOf(accepted)).isEqualTo("ACCEPTED");
    }

    @Test
    void runningTwiceChangesNothingMoreAndEmitsNoEvent() {
        String orgId = newOrg();
        insert(orgId, "expired@example.com", "PENDING", "now() - interval '1 minute'");

        sweep.expirePending();

        assertThat(sweep.expirePending()).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.outbox_events WHERE org_id = ?", Integer.class, orgId))
                .isZero();
    }

    @Test
    void manyMoreThanOneBatchAreAllExpired() {
        String orgId = newOrg();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            ids.add(insert(orgId, "stale-" + i + "@example.com", "PENDING", "now() - interval '1 minute'"));
        }

        assertThat(sweep.expirePending()).isGreaterThanOrEqualTo(25);

        assertThat(ids).allSatisfy(id -> assertThat(statusOf(id)).isEqualTo("EXPIRED"));
    }

    @Test
    void anUnsweptExpiredInviteIsRejectedOnAcceptanceAndDoesNotBlockANewInvite() {
        String orgId = newOrg();
        String owner = fixtures.newMember(orgId, "OWNER", "ACTIVE");
        UUID stale = insert(orgId, "jane@example.com", "PENDING", "now() - interval '1 day'");

        transaction.executeWithoutResult(status -> acceptance.handle(
                OrgInviteAccepted.of(orgId, stale.toString(), "jane-user", "jane@example.com", "Jane", "viewer")));

        assertThat(jdbc.queryForObject(
                        "SELECT payload->>'reason' FROM org_team.outbox_events WHERE org_id = ? AND event_type = ?",
                        String.class,
                        orgId,
                        OrgInviteRejected.TYPE))
                .isEqualTo(OrgInviteRejected.REASON_EXPIRED);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.memberships WHERE org_id = ? AND user_id = 'jane-user'",
                        Integer.class,
                        orgId))
                .isZero();

        InviteDto fresh = inviteService.create(orgId, owner, "jane@example.com", Role.VIEWER);

        assertThat(statusOf(stale)).isEqualTo("EXPIRED");
        assertThat(statusOf(fresh.id())).isEqualTo("PENDING");
    }

    private String newOrg() {
        String orgId = fixtures.newActiveOrg();
        orgIds.add(orgId);
        return orgId;
    }

    private UUID insert(String orgId, String email, String status, String expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO org_team.invites
                            (id, org_id, email, role, invited_by_user_id, status, expires_at, responded_at)
                        VALUES (?, ?, ?, 'VIEWER', 'inviter', ?, %s,
                                CASE WHEN ? = 'PENDING' THEN NULL ELSE now() END)
                        """.formatted(expiresAt), id, orgId, email, status, status);
        return id;
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM org_team.invites WHERE id = ?", String.class, id);
    }
}
