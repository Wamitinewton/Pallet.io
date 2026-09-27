package io.pallet.orgteam.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.events.OrgInviteAccepted;
import io.pallet.common.events.Topics;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.invite.InviteAcceptanceService;
import io.pallet.orgteam.outbox.TopicProbe;
import io.pallet.orgteam.outbox.TopicProbe.Received;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, SignedTokenTestConfiguration.class})
@TestPropertySource(properties = "pallet.orgteam.outbox.enabled=true")
class OrgApiIntegrationTest {

    private static final String COLLECTION = "/api/v1/org-team/orgs";
    private static final String PATH = COLLECTION + "/{orgId}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private InviteAcceptanceService inviteAcceptance;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private JsonMapper jsonMapper;

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
            jdbc.update("DELETE FROM org_team.invites WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM org_team.outbox_events WHERE org_id = ?", orgId);
        });
        fixtures.cleanUp();
    }

    @Test
    void anAccountCreatesATeamOrgItOwnsFromItsOwnTokenAndOrgMemberAddedIsRelayed() throws Exception {
        String userId = newAccount();
        String slug = uniqueSlug();

        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), jsonMapper, Topics.ORG_MEMBER_ADDED)) {
            createAs(userId, "{\"name\":\"  Acme Inc  \",\"slug\":\"" + slug + "\"}")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.name").value("Acme Inc"))
                    .andExpect(jsonPath("$.data.slug").value(slug))
                    .andExpect(jsonPath("$.data.kind").value("TEAM"))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.ownerUserId").value(userId))
                    .andExpect(jsonPath("$.data.counts.members").value(1));
            String orgId = orgIdOfSlug(slug);

            List<Received> relayed = probe.awaitCount(orgId, 1);
            assertThat(relayed.getFirst().body().get("userId").asString()).isEqualTo(userId);
            assertThat(relayed.getFirst().body().get("email").asString()).isEqualTo(emailOf(userId));

            assertThat(jdbc.queryForMap(
                            "SELECT kind, status, owner_user_id FROM org_team.organizations WHERE org_id = ?", orgId))
                    .containsEntry("kind", "TEAM")
                    .containsEntry("status", "ACTIVE")
                    .containsEntry("owner_user_id", userId);
            Map<String, Object> owner = jdbc.queryForMap(
                    "SELECT email, display_name, role, status, profile_synced_at FROM org_team.memberships"
                            + " WHERE org_id = ? AND user_id = ?",
                    orgId,
                    userId);
            assertThat(owner)
                    .containsEntry("email", emailOf(userId))
                    .containsEntry("display_name", "Name " + userId)
                    .containsEntry("role", "OWNER")
                    .containsEntry("status", "ACTIVE");
            assertThat(owner.get("profile_synced_at")).isNotNull();
            assertThat(jdbc.queryForList(
                            "SELECT payload->>'action' FROM org_team.outbox_events"
                                    + " WHERE org_id = ? AND event_type = 'audit.event.recorded'",
                            String.class,
                            orgId))
                    .containsExactly("org.created");

            getAs(orgId, userId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.kind").value("TEAM"));
        }
    }

    @Test
    void oneAccountMayCreateSeveralTeamOrgsWithDerivedSlugs() throws Exception {
        String userId = newAccount();
        String first = uniqueSlug();
        String second = uniqueSlug();

        createAs(userId, "{\"name\":\"" + first + "\"}").andExpect(status().isCreated());
        createAs(userId, "{\"name\":\"" + second + "\"}").andExpect(status().isCreated());

        orgIdOfSlug(first);
        orgIdOfSlug(second);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.organizations WHERE owner_user_id = ? AND kind = 'TEAM'",
                        Integer.class,
                        userId))
                .isEqualTo(2);
    }

    @Test
    void aSlugAlreadyHeldByAnyOrgIsAConflictAndCreatesNothing() throws Exception {
        String userId = newAccount();
        String slug = uniqueSlug();
        createAs(userId, "{\"name\":\"Acme\",\"slug\":\"" + slug + "\"}").andExpect(status().isCreated());
        orgIdOfSlug(slug);
        String personalOrg = fixtures.newPersonalOrg(newAccount());
        String other = newAccount();

        createAs(other, "{\"name\":\"Acme\",\"slug\":\"" + slug + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SLUG_TAKEN"));
        createAs(other, "{\"name\":\"" + personalOrg + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SLUG_TAKEN"));

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.memberships WHERE user_id = ?", Integer.class, other))
                .isZero();
    }

    @Test
    void createRejectsAnInvalidBodyAndAKindField() throws Exception {
        String userId = newAccount();

        createAs(userId, "{\"name\":\"   \"}").andExpect(status().isBadRequest());
        createAs(userId, "{\"name\":\"" + "n".repeat(256) + "\"}").andExpect(status().isBadRequest());
        createAs(userId, "{\"name\":\"Acme\",\"slug\":\"Not A Slug\"}").andExpect(status().isBadRequest());
        createAs(userId, "{\"name\":\"Acme\",\"kind\":\"PERSONAL\"}").andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM org_team.organizations WHERE owner_user_id = ?", Integer.class, userId))
                .isZero();
    }

    @Test
    void myOrgsListsPersonalFirstThenEveryActiveTeamMembershipWithMyRole() throws Exception {
        String userId = newAccount();
        String personalOrg = fixtures.newPersonalOrg(userId);

        String ownedSlug = uniqueSlug();
        createAs(userId, "{\"name\":\"Alpha Team\",\"slug\":\"" + ownedSlug + "\"}")
                .andExpect(status().isCreated());
        String ownedOrg = orgIdOfSlug(ownedSlug);

        String joinedOrg = newOrg();
        jdbc.update("UPDATE org_team.organizations SET name = 'Beta Team' WHERE org_id = ?", joinedOrg);
        String joinedOwner = fixtures.newMember(joinedOrg, "OWNER", "ACTIVE");
        acceptInvite(joinedOrg, joinedOwner, userId);

        String removedFrom = newOrg();
        fixtures.addMember(removedFrom, userId, "ADMIN", "REMOVED");
        String deleted = fixtures.newOrg("DELETED");
        fixtures.addMember(deleted, userId, "ADMIN", "ACTIVE");
        fixtures.newActiveOrg();

        listAs(userId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content.length()").value(3))
                .andExpect(jsonPath("$.data.content[0].orgId").value(personalOrg))
                .andExpect(jsonPath("$.data.content[0].kind").value("PERSONAL"))
                .andExpect(jsonPath("$.data.content[0].myRole").value("OWNER"))
                .andExpect(jsonPath("$.data.content[1].orgId").value(ownedOrg))
                .andExpect(jsonPath("$.data.content[1].name").value("Alpha Team"))
                .andExpect(jsonPath("$.data.content[1].slug").value(ownedSlug))
                .andExpect(jsonPath("$.data.content[1].kind").value("TEAM"))
                .andExpect(jsonPath("$.data.content[1].myRole").value("OWNER"))
                .andExpect(jsonPath("$.data.content[2].orgId").value(joinedOrg))
                .andExpect(jsonPath("$.data.content[2].kind").value("TEAM"))
                .andExpect(jsonPath("$.data.content[2].myRole").value("VIEWER"));
    }

    @Test
    void myOrgsIsScopedToTheCallerAlone() throws Exception {
        String userId = newAccount();
        fixtures.newPersonalOrg(userId);
        String stranger = newAccount();

        listAs(stranger)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
        listAs(userId).andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void myOrgsRejectsAnUnlistedSortField() throws Exception {
        mvc.perform(get(COLLECTION).param("sort", "ownerUserId,asc").header("Authorization", bearer(newAccount())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_SORT"));
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
        patchAs(orgId, owner, "{\"kind\":\"PERSONAL\"}").andExpect(status().isBadRequest());
        patchAs(orgId, owner, "{\"name\":\"Fine\",\"kind\":\"PERSONAL\"}").andExpect(status().isBadRequest());

        assertThat(jdbc.queryForMap(
                        "SELECT name, owner_user_id, kind, status FROM org_team.organizations WHERE org_id = ?", orgId))
                .containsEntry("name", "Org " + orgId)
                .containsEntry("owner_user_id", "owner-" + orgId)
                .containsEntry("kind", "TEAM")
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

    private void acceptInvite(String orgId, String inviter, String invitee) throws Exception {
        mvc.perform(post(PATH + "/invites", orgId)
                        .header(
                                "Authorization",
                                "Bearer "
                                        + OrgTeamTestTokens.forMember(orgId, inviter)
                                                .signed())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + emailOf(invitee) + "\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated());
        UUID inviteId = jdbc.queryForObject("SELECT id FROM org_team.invites WHERE org_id = ?", UUID.class, orgId);
        transaction.executeWithoutResult(status -> inviteAcceptance.handle(OrgInviteAccepted.of(
                orgId, inviteId.toString(), invitee, emailOf(invitee), "Name " + invitee, "viewer")));
    }

    private String newAccount() {
        return "user-" + UUID.randomUUID();
    }

    private static String emailOf(String userId) {
        return userId + "@example.com";
    }

    private static String uniqueSlug() {
        return "team-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String orgIdOfSlug(String slug) {
        String orgId =
                jdbc.queryForObject("SELECT org_id FROM org_team.organizations WHERE slug = ?", String.class, slug);
        orgIds.add(orgId);
        fixtures.track(orgId);
        return orgId;
    }

    private static String bearer(String userId) {
        return "Bearer "
                + OrgTeamTestTokens.forAccount(userId)
                        .withEmail(emailOf(userId).toUpperCase(java.util.Locale.ROOT))
                        .withName("Name " + userId)
                        .signed();
    }

    private ResultActions createAs(String userId, String body) throws Exception {
        return mvc.perform(post(COLLECTION)
                .header("Authorization", bearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions listAs(String userId) throws Exception {
        return mvc.perform(get(COLLECTION).header("Authorization", bearer(userId)));
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
