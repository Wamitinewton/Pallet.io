package io.pallet.orgteam.member;

import io.pallet.orgteam.security.OrgFixtures;
import io.pallet.orgteam.security.OrgTeamTestTokens;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

abstract class MemberIntegrationSupport {

    protected static final String BASE = "/api/v1/org-team/orgs/{orgId}/members";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    protected OrgFixtures fixtures;
    private final List<String> orgIds = new ArrayList<>();

    @BeforeEach
    void setUpFixtures() {
        fixtures = new OrgFixtures(jdbc);
    }

    @AfterEach
    void cleanUpFixtures() {
        orgIds.forEach(orgId -> {
            jdbc.update("DELETE FROM org_team.team_members WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.teams WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.outbox_events WHERE org_id = ?", orgId);
        });
        fixtures.cleanUp();
        orgIds.clear();
    }

    protected TestOrg newTeamOrg() {
        String orgId = fixtures.newActiveOrg();
        orgIds.add(orgId);
        String owner = fixtures.newMember(orgId, "OWNER", "ACTIVE");
        jdbc.update("UPDATE org_team.organizations SET owner_user_id = ? WHERE org_id = ?", owner, orgId);
        return new TestOrg(orgId, owner);
    }

    protected record TestOrg(String orgId, String owner) {}

    protected String bearer(String orgId, String userId) {
        return "Bearer " + OrgTeamTestTokens.forMember(orgId, userId).signed();
    }

    protected String staleBearer(String orgId, String userId) {
        return "Bearer "
                + OrgTeamTestTokens.forMember(orgId, userId)
                        .authenticatedAt(Instant.now().minus(1, ChronoUnit.HOURS))
                        .signed();
    }

    protected ResultActions perform(MockHttpServletRequestBuilder request, String orgId, String userId)
            throws Exception {
        return mvc.perform(request.header("Authorization", bearer(orgId, userId)));
    }

    protected int activeOwners(String orgId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM org_team.memberships WHERE org_id = ? AND role = 'OWNER' AND status = 'ACTIVE'",
                Integer.class,
                orgId);
    }

    protected List<String> outboxTypes(String orgId) {
        return jdbc.queryForList(
                "SELECT event_type FROM org_team.outbox_events WHERE org_id = ? ORDER BY id", String.class, orgId);
    }

    protected List<Map<String, Object>> events(String orgId, String eventType) {
        return jdbc.queryForList("""
                        SELECT id, payload->>'userId' AS user_id, payload->>'previousRole' AS previous_role,
                               payload->>'newRole' AS new_role, payload->>'email' AS email,
                               payload->>'action' AS action
                        FROM org_team.outbox_events WHERE org_id = ? AND event_type = ? ORDER BY id
                        """, orgId, eventType);
    }

    protected void insertTeamAssignment(String orgId, String userId) {
        UUID teamId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO org_team.teams (id, org_id, name, slug) VALUES (?, ?, 'platform', ?)",
                teamId,
                orgId,
                "t-" + teamId);
        jdbc.update(
                "INSERT INTO org_team.team_members (team_id, user_id, org_id, added_by) VALUES (?, ?, ?, 'seed')",
                teamId,
                userId,
                orgId);
    }
}
