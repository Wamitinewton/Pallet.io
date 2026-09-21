package io.pallet.orgteam.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import io.pallet.common.events.OrgProvisioned;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.member.MembershipRepository;
import io.pallet.orgteam.org.OrgService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class OrgPurgeJobIntegrationTest {

    @Autowired
    private OrgPurgeJob job;

    @Autowired
    private OrgService orgService;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private MembershipRepository membershipRepository;

    private final List<String> orgIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        orgIds.forEach(orgId -> {
            jdbc.update("DELETE FROM org_team.team_members WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.apps WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.teams WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.invites WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.memberships WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.organizations WHERE org_id = ?", orgId);
        });
        orgIds.clear();
    }

    @Test
    void aDeletedOrgPastTheWindowLosesEveryChildRowAndKeepsATombstone() {
        String orgId = seedOrg("DELETED", 31);

        assertThat(job.purgeExpired()).isGreaterThanOrEqualTo(1);

        assertThat(childRows(orgId)).isZero();
        Map<String, Object> org = jdbc.queryForMap("""
                        SELECT name, slug, status, owner_user_id, deleted_at IS NOT NULL AS has_deleted_at,
                               purged_at IS NOT NULL AS purged
                        FROM org_team.organizations WHERE org_id = ?
                        """, orgId);
        assertThat(org)
                .containsEntry("name", OrgPurgeJob.PURGED_NAME)
                .containsEntry("slug", "slug-" + orgId)
                .containsEntry("status", "DELETED")
                .containsEntry("owner_user_id", "owner-" + orgId)
                .containsEntry("has_deleted_at", true)
                .containsEntry("purged", true);
    }

    @Test
    void theTombstoneKeepsTheSlugReservedAndAReplayedProvisioningInert() {
        String orgId = seedOrg("DELETED", 31);
        job.purgeExpired();

        transaction.executeWithoutResult(status -> orgService.provision(
                OrgProvisioned.of(orgId, "Acme", "slug-" + orgId, "new-owner", "new@example.com", "New Owner")));

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.memberships WHERE org_id = ?", Integer.class, orgId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT name FROM org_team.organizations WHERE org_id = ?", String.class, orgId))
                .isEqualTo(OrgPurgeJob.PURGED_NAME);

        String squatter = "org-" + UUID.randomUUID();
        orgIds.add(squatter);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> orgService.provision(
                        OrgProvisioned.of(squatter, "Squatter", "slug-" + orgId, "s", "s@example.com", "S"))))
                .hasMessageContaining("Slug of organization");
    }

    @Test
    void anOrgInsideTheWindowAndAnActiveOrgAreUntouched() {
        String recentlyDeleted = seedOrg("DELETED", 29);
        String active = seedOrg("ACTIVE", 0);

        job.purgeExpired();

        assertThat(childRows(recentlyDeleted)).isPositive();
        assertThat(childRows(active)).isPositive();
        assertThat(isPurged(recentlyDeleted)).isFalse();
        assertThat(isPurged(active)).isFalse();
    }

    @Test
    void aSecondRunFindsNothingToDo() {
        seedOrg("DELETED", 31);
        job.purgeExpired();

        assertThat(job.purgeExpired()).isZero();
    }

    @Test
    void aFailureMidwayRollsBackThatOrgEntirely() {
        String orgId = seedOrg("DELETED", 31);
        int before = childRows(orgId);
        doThrow(new IllegalStateException("memberships down"))
                .when(membershipRepository)
                .deleteAllForOrg(anyString());

        assertThat(job.purgeExpired()).isZero();

        assertThat(childRows(orgId)).isEqualTo(before);
        assertThat(isPurged(orgId)).isFalse();
    }

    private String seedOrg(String status, int deletedDaysAgo) {
        String orgId = "org-" + UUID.randomUUID();
        orgIds.add(orgId);
        jdbc.update("""
                        INSERT INTO org_team.organizations
                            (org_id, name, slug, owner_user_id, status, deleted_at)
                        VALUES (?, 'Acme', ?, ?, ?,
                                CASE WHEN ? = 'DELETED' THEN now() - make_interval(days => ?) END)
                        """, orgId, "slug-" + orgId, "owner-" + orgId, status, status, deletedDaysAgo);
        String memberStatus = "DELETED".equals(status) ? "REMOVED" : "ACTIVE";
        jdbc.update("""
                        INSERT INTO org_team.memberships (org_id, user_id, email, display_name, role, status)
                        VALUES (?, 'owner', 'owner@example.com', 'Owner', 'VIEWER', ?),
                               (?, 'dev', 'dev@example.com', 'Dev', 'VIEWER', ?)
                        """, orgId, memberStatus, orgId, memberStatus);
        UUID teamId = UUID.randomUUID();
        jdbc.update("INSERT INTO org_team.teams (id, org_id, name, slug) VALUES (?, ?, 'Core', 'core')", teamId, orgId);
        jdbc.update("""
                        INSERT INTO org_team.team_members (team_id, user_id, org_id, added_by)
                        VALUES (?, 'dev', ?, 'owner')
                        """, teamId, orgId);
        jdbc.update("""
                        INSERT INTO org_team.apps (id, org_id, team_id, name, slug, cloud_provider, region, status)
                        VALUES (?, ?, ?, 'Web', 'web', 'AWS', 'us-east-1', 'ACTIVE')
                        """, UUID.randomUUID(), orgId, teamId);
        jdbc.update("""
                        INSERT INTO org_team.invites
                            (id, org_id, email, role, invited_by_user_id, status, expires_at, responded_at)
                        VALUES (?, ?, 'guest@example.com', 'VIEWER', 'owner', 'REVOKED', now(), now())
                        """, UUID.randomUUID(), orgId);
        return orgId;
    }

    private int childRows(String orgId) {
        int total = 0;
        for (String table : List.of("team_members", "apps", "teams", "invites", "memberships")) {
            total += jdbc.queryForObject(
                    "SELECT count(*) FROM org_team." + table + " WHERE org_id = ?", Integer.class, orgId);
        }
        return total;
    }

    private boolean isPurged(String orgId) {
        return jdbc.queryForObject(
                "SELECT purged_at IS NOT NULL FROM org_team.organizations WHERE org_id = ?", Boolean.class, orgId);
    }
}
