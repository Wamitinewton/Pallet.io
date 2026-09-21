package io.pallet.orgteam.observability;

import io.pallet.orgteam.security.OrgFixtures;
import io.pallet.orgteam.security.OrgTeamTestTokens;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

abstract class ObservabilityIntegrationSupport {

    protected static final String API = "/api/v1/org-team";

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
            jdbc.update("DELETE FROM org_team.apps WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.team_members WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.teams WHERE org_id = ?", orgId);
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

    protected static String bearer(String orgId, String userId) {
        return "Bearer " + OrgTeamTestTokens.forMember(orgId, userId).signed();
    }

    protected ResultActions perform(MockHttpServletRequestBuilder request, String orgId, String userId)
            throws Exception {
        return mvc.perform(request.header("Authorization", bearer(orgId, userId)));
    }
}
