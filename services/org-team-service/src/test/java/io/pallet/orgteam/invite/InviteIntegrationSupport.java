package io.pallet.orgteam.invite;

import io.pallet.orgteam.config.InviteSigningProperties;
import io.pallet.orgteam.security.OrgFixtures;
import io.pallet.orgteam.security.OrgTeamTestTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

abstract class InviteIntegrationSupport {

    protected static final String BASE = "/api/v1/org-team/orgs/{orgId}/invites";
    private static final String TOKEN_MARKER = "/invites/";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected InviteSigningProperties signing;

    protected OrgFixtures fixtures;
    private final List<String> orgIds = new ArrayList<>();

    @BeforeEach
    void setUpFixtures() {
        fixtures = new OrgFixtures(jdbc);
    }

    @AfterEach
    void cleanUpFixtures() {
        orgIds.forEach(orgId -> {
            jdbc.update("DELETE FROM org_team.invites WHERE org_id = ?", orgId);
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

    protected ResultActions perform(MockHttpServletRequestBuilder request, String orgId, String userId)
            throws Exception {
        return mvc.perform(request.header(
                "Authorization",
                "Bearer " + OrgTeamTestTokens.forMember(orgId, userId).signed()));
    }

    protected ResultActions invite(String orgId, String actor, String email, String role) throws Exception {
        return perform(
                MockMvcRequestBuilders.post(BASE, orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}"),
                orgId,
                actor);
    }

    protected List<Map<String, Object>> outbox(String orgId) {
        return jdbc.queryForList("""
                        SELECT event_type, sensitive, payload->>'dedupeKey' AS dedupe_key,
                               payload->'variables'->>'acceptUrl' AS accept_url, payload->>'action' AS action
                        FROM org_team.outbox_events WHERE org_id = ? ORDER BY id
                        """, orgId);
    }

    protected List<String> acceptUrls(String orgId) {
        return outbox(orgId).stream()
                .map(row -> (String) row.get("accept_url"))
                .filter(url -> url != null)
                .toList();
    }

    protected String tokenOf(String acceptUrl) {
        return acceptUrl.substring(acceptUrl.indexOf(TOKEN_MARKER) + TOKEN_MARKER.length());
    }

    protected String statusOf(UUID inviteId) {
        return jdbc.queryForObject("SELECT status FROM org_team.invites WHERE id = ?", String.class, inviteId);
    }

    protected void insertPendingInvite(String orgId, String email, String invitedBy) {
        jdbc.update("""
                        INSERT INTO org_team.invites (id, org_id, email, role, invited_by_user_id, status, expires_at)
                        VALUES (?, ?, ?, 'VIEWER', ?, 'PENDING', now() + interval '1 day')
                        """, UUID.randomUUID(), orgId, email, invitedBy);
    }
}
