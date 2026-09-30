package io.pallet.gitintegration.installation;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.audit.AuditEvents;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.ReadModelFixtures;
import io.pallet.gitintegration.support.Tokens;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, GitHubApiStub.Properties.class, Tokens.LocalDecoder.class})
class InstallationControllerIntegrationTest {

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private ReadModelFixtures fixtures;
    private InstallationApi api;
    private String orgA;
    private String orgB;
    private long shared;

    @BeforeEach
    void setUp() {
        fixtures = new ReadModelFixtures(jdbc);
        api = new InstallationApi(mvc, json, github);
        orgA = fixtures.newOrg();
        orgB = fixtures.newOrg();
        shared = fixtures.newInstallation();
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanUp();
    }

    @Test
    void aDeveloperCanNeitherStartAnInstallNorLinkNorUnlink() throws Exception {
        fixtures.linkInstallation(shared, orgA, "ACTIVE");
        api.as(fixtures.newMember(orgA, "developer", "ACTIVE"));

        api.assertError(api.startInstall(orgA), 403).hasErrorCode("INSUFFICIENT_ROLE");
        api.assertError(api.linkExisting(orgA, shared), 403).hasErrorCode("INSUFFICIENT_ROLE");
        api.assertError(api.unlink(orgA, shared), 403).hasErrorCode("INSUFFICIENT_ROLE");

        assertThat(linkStatus(orgA)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM git_integration.authorization_states WHERE org_id = ?",
                        Integer.class,
                        orgA))
                .isZero();
    }

    @Test
    void anAdminStartsAnInstallAtTheAppsGitHubPage() throws Exception {
        api.as(fixtures.newMember(orgA, "admin", "ACTIVE"));

        MockHttpServletResponse started = api.startInstall(orgA);

        assertThat(started.getStatus()).isEqualTo(201);
        String installUrl = api.data(started).path("installUrl").asString();
        assertThat(installUrl).startsWith(GitHubApiStub.baseUrl() + "/apps/pallet-test/installations/new?state=");
        assertThat(InstallationApi.stateOf(installUrl)).isNotBlank();
        assertThat(api.data(started).path("expiresAt").isMissingNode()).isFalse();
    }

    @Test
    void aViewerListsOnlyItsOwnOrgsActiveLinks() throws Exception {
        long onlyA = fixtures.newInstallation();
        long unlinkedB = fixtures.newInstallation();
        fixtures.linkInstallation(shared, orgA, "ACTIVE");
        fixtures.linkInstallation(onlyA, orgA, "ACTIVE");
        fixtures.linkInstallation(shared, orgB, "ACTIVE");
        fixtures.linkInstallation(unlinkedB, orgB, "UNLINKED");

        api.as(fixtures.newMember(orgB, "viewer", "ACTIVE"));
        MockHttpServletResponse listed = api.list(orgB);

        assertThat(listed.getStatus()).isEqualTo(200);
        JsonNode page = api.data(listed);
        assertThat(page.path("totalElements").asLong()).isOne();
        JsonNode link = page.path("content").get(0);
        assertThat(link.path("installationId").asLong()).isEqualTo(shared);
        assertThat(link.path("accountLogin").asString()).isEqualTo("acme");
        assertThat(link.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(link.path("linkedByUserId").asString()).isEqualTo("user-linker");
    }

    @Test
    void anOrgThatHasNotLinkedASharedInstallationSeesNothingOfIt() throws Exception {
        fixtures.linkInstallation(shared, orgA, "ACTIVE");
        api.as(fixtures.newMember(orgB, "admin", "ACTIVE"));

        assertThat(api.data(api.list(orgB)).path("totalElements").asLong()).isZero();
        api.assertError(api.unlink(orgB, shared), 404).hasErrorCode("INSTALLATION_NOT_FOUND");
        assertThat(linkStatus(orgA)).isEqualTo("ACTIVE");
    }

    @Test
    void unlinkingDisconnectsOnlyThisOrgsRepoLinksAndLeavesTheOtherOrgsConnection() throws Exception {
        fixtures.linkInstallation(shared, orgA, "ACTIVE");
        fixtures.linkInstallation(shared, orgB, "ACTIVE");
        UUID appA1 = fixtures.newRepoLink(orgA, shared, 42);
        UUID appA2 = fixtures.newRepoLink(orgA, shared, 43);
        UUID appB = fixtures.newRepoLink(orgB, shared, 42);
        String adminA = fixtures.newMember(orgA, "admin", "ACTIVE");
        api.as(adminA);

        assertThat(api.unlink(orgA, shared).getStatus()).isEqualTo(204);

        assertThat(linkStatus(orgA)).isEqualTo("UNLINKED");
        assertThat(repoLink(appA1))
                .containsEntry("status", "DISCONNECTED")
                .containsEntry("disconnect_reason", "INSTALLATION_UNLINKED");
        assertThat(repoLink(appA2)).containsEntry("status", "DISCONNECTED");
        assertThat(linkStatus(orgB)).isEqualTo("ACTIVE");
        assertThat(repoLink(appB)).containsEntry("status", "ACTIVE").containsEntry("disconnect_reason", null);
        assertThat(unusedSince()).isNull();
        List<Map<String, Object>> audits = unlinkAudits(orgA);
        assertThat(audits)
                .singleElement()
                .satisfies(audit ->
                        assertThat(audit).containsEntry("actor", adminA).containsEntry("disconnected", "2"));
        assertThat(unlinkAudits(orgB)).isEmpty();
    }

    @Test
    void theLastUnlinkStartsTheUnusedClock() throws Exception {
        fixtures.linkInstallation(shared, orgA, "ACTIVE");
        fixtures.linkInstallation(shared, orgB, "ACTIVE");

        api.as(fixtures.newMember(orgA, "admin", "ACTIVE"));
        assertThat(api.unlink(orgA, shared).getStatus()).isEqualTo(204);
        assertThat(unusedSince()).isNull();

        api.as(fixtures.newMember(orgB, "owner", "ACTIVE"));
        assertThat(api.unlink(orgB, shared).getStatus()).isEqualTo(204);
        assertThat(unusedSince()).isNotNull();

        api.assertError(api.unlink(orgB, shared), 404).hasErrorCode("INSTALLATION_NOT_FOUND");
        assertThat(api.data(api.list(orgB)).path("totalElements").asLong()).isZero();
    }

    private String linkStatus(String org) {
        return jdbc.queryForObject(
                "SELECT status FROM git_integration.installation_links WHERE installation_id = ? AND org_id = ?",
                String.class,
                shared,
                org);
    }

    private Map<String, Object> repoLink(UUID appId) {
        return jdbc.queryForMap("SELECT * FROM git_integration.repo_links WHERE app_id = ?", appId);
    }

    private Object unusedSince() {
        return jdbc.queryForObject(
                "SELECT unused_since FROM git_integration.installations WHERE installation_id = ?",
                Object.class,
                shared);
    }

    private List<Map<String, Object>> unlinkAudits(String org) {
        return jdbc.queryForList("""
                SELECT payload ->> 'actor' AS actor,
                       payload -> 'context' ->> 'repoLinksDisconnected' AS disconnected
                  FROM git_integration.outbox_events
                 WHERE org_id = ? AND event_type = ? AND payload ->> 'action' = ?
                """, org, AuditEventRecorded.TYPE, AuditEvents.INSTALLATION_UNLINKED);
    }
}
