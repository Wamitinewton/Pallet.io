package io.pallet.orgteam.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.events.OrgDeleted;
import io.pallet.common.events.OrgInviteAccepted;
import io.pallet.common.events.OrgInviteRejected;
import io.pallet.common.events.OrgProvisioned;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.invite.InviteAcceptanceService;
import io.pallet.orgteam.invite.InviteRepository;
import io.pallet.orgteam.security.OrgFixtures;
import io.pallet.orgteam.security.OrgTeamTestTokens;
import io.pallet.orgteam.security.SignedTokenTestConfiguration;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, SignedTokenTestConfiguration.class})
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class OrgDeletionIntegrationTest {

    private static final String PATH = "/api/v1/org-team/orgs/{orgId}";
    private static final String CONFIRM_HEADER = "X-Confirm-Slug";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private OrgService orgService;

    @Autowired
    private InviteAcceptanceService inviteAcceptance;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @MockitoSpyBean
    private JsonMapper jsonMapper;

    @MockitoSpyBean
    private InviteRepository inviteRepository;

    private OrgFixtures fixtures;
    private final List<String> orgIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixtures = new OrgFixtures(jdbc);
    }

    @AfterEach
    void tearDown() {
        orgIds.forEach(orgId -> {
            jdbc.update("DELETE FROM org_team.team_members WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.apps WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.teams WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.invites WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.outbox_events WHERE org_id = ?", orgId);
        });
        fixtures.cleanUp();
        orgIds.clear();
    }

    @Test
    void aNonOwnerCannotDeleteAndNothingChanges() throws Exception {
        Seed seed = seed(3, 2, 2);

        deleteAs(seed.orgId(), seed.admin(), seed.slug())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));

        assertUntouched(seed);
    }

    @Test
    void anOwnerWithAStaleAuthenticationIsAskedToSignInAgain() throws Exception {
        Seed seed = seed(3, 2, 2);

        mvc.perform(delete(PATH, seed.orgId())
                        .header("Authorization", staleBearer(seed.orgId(), seed.owner()))
                        .header(CONFIRM_HEADER, seed.slug()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("REAUTHENTICATION_REQUIRED"));

        assertUntouched(seed);
    }

    @Test
    void aMissingOrWrongSlugIsRefusedAndNothingChanges() throws Exception {
        Seed seed = seed(3, 2, 2);

        mvc.perform(delete(PATH, seed.orgId()).header("Authorization", bearer(seed.orgId(), seed.owner())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CONFIRMATION_MISMATCH"));
        deleteAs(seed.orgId(), seed.owner(), "not-" + seed.slug())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CONFIRMATION_MISMATCH"));

        assertUntouched(seed);
    }

    @Test
    void deletingCascadesThroughEveryAggregateAndEmitsTheEventsInOrder() throws Exception {
        Seed seed = seed(3, 2, 2);

        deleteAs(seed.orgId(), seed.owner(), seed.slug()).andExpect(status().isNoContent());

        assertThat(jdbc.queryForMap("""
                        SELECT status, deleted_by, deleted_at IS NOT NULL AS stamped
                        FROM org_team.organizations WHERE org_id = ?
                        """, seed.orgId()))
                .containsEntry("status", "DELETED")
                .containsEntry("deleted_by", seed.owner())
                .containsEntry("stamped", true);
        assertThat(count("memberships", seed.orgId(), "status <> 'REMOVED'")).isZero();
        assertThat(count("memberships", seed.orgId(), "removed_by = '" + seed.owner() + "' AND removed_at IS NOT NULL"))
                .isEqualTo(4);
        assertThat(count("team_members", seed.orgId(), "true")).isZero();
        assertThat(count("teams", seed.orgId(), "true")).isEqualTo(1);
        assertThat(count("invites", seed.orgId(), "status = 'PENDING'")).isZero();
        assertThat(count("invites", seed.orgId(), "status = 'REVOKED' AND responded_at IS NOT NULL"))
                .isEqualTo(2);
        assertThat(count("apps", seed.orgId(), "status = 'ACTIVE'")).isZero();
        assertThat(count("apps", seed.orgId(), "status = 'DELETED' AND deleted_at IS NOT NULL"))
                .isEqualTo(2);

        assertThat(outboxTypes(seed.orgId()))
                .containsExactly("app.deleted", "app.deleted", "org.deleted", "audit.event.recorded");
        assertThat(jdbc.queryForObject("""
                        SELECT payload->>'deletedByUserId' FROM org_team.outbox_events
                        WHERE org_id = ? AND event_type = 'org.deleted'
                        """, String.class, seed.orgId())).isEqualTo(seed.owner());
    }

    @Test
    void afterDeletionEveryPathIsNotFoundEvenForTheFormerOwner() throws Exception {
        Seed seed = seed(2, 1, 1);
        deleteAs(seed.orgId(), seed.owner(), seed.slug()).andExpect(status().isNoContent());

        mvc.perform(get(PATH, seed.orgId()).header("Authorization", bearer(seed.orgId(), seed.owner())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ORG_NOT_FOUND"));
        mvc.perform(get(PATH + "/members", seed.orgId()).header("Authorization", bearer(seed.orgId(), seed.owner())))
                .andExpect(status().isNotFound());
        mvc.perform(get(PATH + "/apps", seed.orgId()).header("Authorization", bearer(seed.orgId(), seed.admin())))
                .andExpect(status().isNotFound());
        deleteAs(seed.orgId(), seed.owner(), seed.slug())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ORG_NOT_FOUND"));
    }

    @Test
    void anInviteAcceptedAfterDeletionIsRepudiatedAsOrgDeleted() throws Exception {
        Seed seed = seed(2, 1, 1);
        UUID inviteId = jdbc.queryForObject(
                "SELECT id FROM org_team.invites WHERE org_id = ? LIMIT 1", UUID.class, seed.orgId());
        String email = jdbc.queryForObject("SELECT email FROM org_team.invites WHERE id = ?", String.class, inviteId);
        deleteAs(seed.orgId(), seed.owner(), seed.slug()).andExpect(status().isNoContent());

        transaction.executeWithoutResult(status -> inviteAcceptance.handle(
                OrgInviteAccepted.of(seed.orgId(), inviteId.toString(), "late-user", email, "Late User", "viewer")));

        assertThat(count("memberships", seed.orgId(), "user_id = 'late-user'")).isZero();
        assertThat(jdbc.queryForObject("""
                        SELECT payload->>'reason' FROM org_team.outbox_events
                        WHERE org_id = ? AND event_type = ?
                        """, String.class, seed.orgId(), OrgInviteRejected.TYPE))
                .isEqualTo(OrgInviteRejected.REASON_ORG_DELETED);
    }

    @Test
    void aReplayedProvisioningDoesNotResurrectTheOrgAndItsSlugStaysReserved() throws Exception {
        Seed seed = seed(2, 1, 1);
        deleteAs(seed.orgId(), seed.owner(), seed.slug()).andExpect(status().isNoContent());

        transaction.executeWithoutResult(status -> orgService.provision(
                OrgProvisioned.of(seed.orgId(), "Acme", seed.slug(), "new-owner", "new@example.com", "New")));
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM org_team.organizations WHERE org_id = ?", String.class, seed.orgId()))
                .isEqualTo("DELETED");
        assertThat(count("memberships", seed.orgId(), "user_id = 'new-owner'")).isZero();

        String otherOrg = "org-" + UUID.randomUUID();
        orgIds.add(otherOrg);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> orgService.provision(
                        OrgProvisioned.of(otherOrg, "Squatter", seed.slug(), "squatter", "s@example.com", "S"))))
                .isInstanceOf(OrgSlugConflictException.class);
    }

    @Test
    void aFailureAtAnyStepRollsTheWholeDeletionBack() throws Exception {
        Seed seed = seed(3, 2, 2);

        doThrow(new IllegalStateException("invites down"))
                .when(inviteRepository)
                .revokeAllPending(anyString(), any());
        deleteAs(seed.orgId(), seed.owner(), seed.slug()).andExpect(status().isInternalServerError());
        assertUntouched(seed);
        reset(inviteRepository);

        doThrow(new IllegalStateException("outbox down")).when(jsonMapper).writeValueAsString(any(OrgDeleted.class));
        deleteAs(seed.orgId(), seed.owner(), seed.slug()).andExpect(status().isInternalServerError());
        assertUntouched(seed);
    }

    @Test
    void theNumberOfStatementsDoesNotGrowWithTheNumberOfMembers() throws Exception {
        Seed small = seed(5, 1, 1);
        Seed large = seed(300, 1, 1);
        Statistics statistics =
                entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        statistics.clear();
        deleteAs(small.orgId(), small.owner(), small.slug()).andExpect(status().isNoContent());
        long smallCount = statistics.getPrepareStatementCount();

        statistics.clear();
        deleteAs(large.orgId(), large.owner(), large.slug()).andExpect(status().isNoContent());
        long largeCount = statistics.getPrepareStatementCount();

        assertThat(largeCount).isEqualTo(smallCount);
    }

    private void assertUntouched(Seed seed) {
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM org_team.organizations WHERE org_id = ?", String.class, seed.orgId()))
                .isEqualTo("ACTIVE");
        assertThat(count("memberships", seed.orgId(), "status = 'ACTIVE'")).isEqualTo(seed.members());
        assertThat(count("team_members", seed.orgId(), "true")).isEqualTo(seed.members());
        assertThat(count("invites", seed.orgId(), "status = 'PENDING'")).isEqualTo(seed.invites());
        assertThat(count("apps", seed.orgId(), "status = 'ACTIVE'")).isEqualTo(seed.apps());
        assertThat(outboxTypes(seed.orgId())).isEmpty();
    }

    private Seed seed(int extraMembers, int invites, int apps) {
        String orgId = fixtures.newActiveOrg();
        orgIds.add(orgId);
        String owner = fixtures.newMember(orgId, "OWNER", "ACTIVE");
        jdbc.update("UPDATE org_team.organizations SET owner_user_id = ? WHERE org_id = ?", owner, orgId);
        String admin = fixtures.newMember(orgId, "ADMIN", "ACTIVE");
        List<String> members = new ArrayList<>(List.of(owner, admin));
        for (int i = 1; i < extraMembers; i++) {
            members.add(fixtures.newMember(orgId, "VIEWER", "ACTIVE"));
        }

        UUID teamId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO org_team.teams (id, org_id, name, slug) VALUES (?, ?, 'platform', 'platform')",
                teamId,
                orgId);
        jdbc.batchUpdate(
                "INSERT INTO org_team.team_members (team_id, user_id, org_id, added_by) VALUES (?, ?, ?, 'seed')",
                members.stream().map(user -> new Object[] {teamId, user, orgId}).toList());

        for (int i = 0; i < invites; i++) {
            jdbc.update("""
                            INSERT INTO org_team.invites (id, org_id, email, role, invited_by_user_id, status, expires_at)
                            VALUES (?, ?, ?, 'VIEWER', ?, 'PENDING', now() + interval '1 day')
                            """, UUID.randomUUID(), orgId, "invitee-" + i + "@example.com", owner);
        }
        for (int i = 0; i < apps; i++) {
            jdbc.update("""
                            INSERT INTO org_team.apps (id, org_id, team_id, name, slug, cloud_provider, region, status)
                            VALUES (?, ?, ?, ?, ?, 'AWS', 'us-east-1', 'ACTIVE')
                            """, UUID.randomUUID(), orgId, teamId, "app-" + i, "app-" + i);
        }
        return new Seed(orgId, orgId, owner, admin, members.size(), invites, apps);
    }

    private ResultActions deleteAs(String orgId, String userId, String slug) throws Exception {
        return mvc.perform(delete(PATH, orgId)
                .header("Authorization", bearer(orgId, userId))
                .header(CONFIRM_HEADER, slug));
    }

    private static String bearer(String orgId, String userId) {
        return "Bearer " + OrgTeamTestTokens.forMember(orgId, userId).signed();
    }

    private static String staleBearer(String orgId, String userId) {
        return "Bearer "
                + OrgTeamTestTokens.forMember(orgId, userId)
                        .authenticatedAt(Instant.now().minus(1, ChronoUnit.HOURS))
                        .signed();
    }

    private int count(String table, String orgId, String condition) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM org_team." + table + " WHERE org_id = ? AND " + condition, Integer.class, orgId);
    }

    private List<String> outboxTypes(String orgId) {
        return jdbc.queryForList(
                "SELECT event_type FROM org_team.outbox_events WHERE org_id = ? ORDER BY id", String.class, orgId);
    }

    private record Seed(String orgId, String slug, String owner, String admin, int members, int invites, int apps) {}
}
