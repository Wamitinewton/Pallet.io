package io.pallet.orgteam.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.security.OrgFixtures;
import io.pallet.orgteam.security.OrgTeamTestTokens;
import io.pallet.orgteam.security.SignedTokenTestConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, SignedTokenTestConfiguration.class})
class OrgApiIntegrationTest {

    private static final String PATH = "/api/v1/org-team/orgs/{orgId}";

    @Autowired
    private MockMvc mvc;

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
            jdbc.update("DELETE FROM org_team.apps WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.teams WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.outbox_events WHERE org_id = ?", orgId);
        });
        fixtures.cleanUp();
    }

    @Test
    void anOwnerReadsTheOrgWithLiveCounts() throws Exception {
        String orgId = newOrg();
        String owner = fixtures.newMember(orgId, "OWNER", "ACTIVE");
        fixtures.newMember(orgId, "DEVELOPER", "ACTIVE");
        fixtures.newMember(orgId, "VIEWER", "REMOVED");
        insertTeam(orgId, "platform");
        insertTeam(orgId, "data");
        insertApp(orgId, "web", "ACTIVE");
        insertApp(orgId, "old", "DELETED");

        getAs(orgId, owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orgId").value(orgId))
                .andExpect(jsonPath("$.data.slug").value(orgId))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.ownerUserId").value("owner-" + orgId))
                .andExpect(jsonPath("$.data.counts.members").value(2))
                .andExpect(jsonPath("$.data.counts.teams").value(2))
                .andExpect(jsonPath("$.data.counts.apps").value(1))
                .andExpect(jsonPath("$.data.version").doesNotExist());
    }

    @Test
    void aViewerMayReadButAnAdminRenamesAndAnAuditEventIsQueued() throws Exception {
        String orgId = newOrg();
        String viewer = fixtures.newMember(orgId, "VIEWER", "ACTIVE");
        String admin = fixtures.newMember(orgId, "ADMIN", "ACTIVE");

        getAs(orgId, viewer).andExpect(status().isOk());
        patchAs(orgId, admin, "{\"name\":\"  Renamed Org  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Renamed Org"));

        getAs(orgId, viewer).andExpect(jsonPath("$.data.name").value("Renamed Org"));
        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT payload->>'action' AS action, payload->>'actor' AS actor, payload->>'resource' AS resource,"
                        + " payload->'context'->>'to' AS renamed_to"
                        + " FROM org_team.outbox_events WHERE org_id = ? AND event_type = 'audit.event.recorded'",
                orgId);
        assertThat(audit)
                .containsEntry("action", "org.renamed")
                .containsEntry("actor", admin)
                .containsEntry("resource", orgId)
                .containsEntry("renamed_to", "Renamed Org");
    }

    @Test
    void renamingToTheCurrentNameIsAnOkNoOpWithoutAnAuditEvent() throws Exception {
        String orgId = newOrg();
        String owner = fixtures.newMember(orgId, "OWNER", "ACTIVE");

        patchAs(orgId, owner, "{\"name\":\"Org " + orgId + "\"}").andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.outbox_events WHERE org_id = ?", Integer.class, orgId))
                .isZero();
    }

    @Test
    void aDeveloperCannotRename() throws Exception {
        String orgId = newOrg();
        String developer = fixtures.newMember(orgId, "DEVELOPER", "ACTIVE");

        patchAs(orgId, developer, "{\"name\":\"Nope\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));
    }

    @Test
    void anotherOrgsIdIsNotFound() throws Exception {
        String ownOrg = newOrg();
        String otherOrg = newOrg();
        String owner = fixtures.newMember(ownOrg, "OWNER", "ACTIVE");

        mvc.perform(get(PATH, otherOrg)
                        .header(
                                "Authorization",
                                "Bearer "
                                        + OrgTeamTestTokens.forMember(ownOrg, owner)
                                                .signed()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ORG_NOT_FOUND"));
    }

    @Test
    void patchRejectsFieldsThatMayNeverBeAssigned() throws Exception {
        String orgId = newOrg();
        String owner = fixtures.newMember(orgId, "OWNER", "ACTIVE");

        patchAs(orgId, owner, "{\"slug\":\"x\"}").andExpect(status().isBadRequest());
        patchAs(orgId, owner, "{\"name\":\"Fine\",\"ownerUserId\":\"someone-else\"}")
                .andExpect(status().isBadRequest());
        patchAs(orgId, owner, "{\"name\":\"Fine\",\"status\":\"DELETED\"}").andExpect(status().isBadRequest());

        assertThat(jdbc.queryForMap(
                        "SELECT name, owner_user_id, status FROM org_team.organizations WHERE org_id = ?", orgId))
                .containsEntry("name", "Org " + orgId)
                .containsEntry("owner_user_id", "owner-" + orgId)
                .containsEntry("status", "ACTIVE");
    }

    @Test
    void patchRejectsABlankOrOverlongName() throws Exception {
        String orgId = newOrg();
        String owner = fixtures.newMember(orgId, "OWNER", "ACTIVE");

        patchAs(orgId, owner, "{\"name\":\"   \"}").andExpect(status().isBadRequest());
        patchAs(orgId, owner, "{\"name\":\"" + "n".repeat(256) + "\"}").andExpect(status().isBadRequest());
    }

    private String newOrg() {
        String orgId = fixtures.newActiveOrg();
        orgIds.add(orgId);
        return orgId;
    }

    private ResultActions getAs(String orgId, String userId) throws Exception {
        return mvc.perform(get(PATH, orgId)
                .header(
                        "Authorization",
                        "Bearer " + OrgTeamTestTokens.forMember(orgId, userId).signed()));
    }

    private ResultActions patchAs(String orgId, String userId, String body) throws Exception {
        return mvc.perform(patch(PATH, orgId)
                .header(
                        "Authorization",
                        "Bearer " + OrgTeamTestTokens.forMember(orgId, userId).signed())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void insertTeam(String orgId, String slug) {
        jdbc.update(
                "INSERT INTO org_team.teams (id, org_id, name, slug) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(),
                orgId,
                slug,
                slug);
    }

    private void insertApp(String orgId, String slug, String status) {
        jdbc.update("""
                        INSERT INTO org_team.apps (id, org_id, name, slug, cloud_provider, region, status)
                        VALUES (?, ?, ?, ?, 'AWS', 'us-east-1', ?)
                        """, UUID.randomUUID(), orgId, slug, slug, status);
    }
}
