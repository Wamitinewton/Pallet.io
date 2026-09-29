package io.pallet.gitintegration.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.audit.AuditEvents;
import io.pallet.gitintegration.session.GitHubUserSessionStore;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.ReadModelFixtures;
import io.pallet.gitintegration.support.Tokens;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, Tokens.LocalDecoder.class})
class InstallationServiceIntegrationTest {

    private static final long GITHUB_USER_ID = 9100001L;

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @DynamicPropertySource
    static void github(DynamicPropertyRegistry registry) {
        GitHubApiStub.register(registry);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GitHubUserSessionStore sessions;

    private ReadModelFixtures fixtures;
    private InstallationApi api;
    private final List<String> subjects = new ArrayList<>();
    private String orgId;
    private String admin;
    private long installationId;

    @BeforeEach
    void setUp() {
        fixtures = new ReadModelFixtures(jdbc);
        api = new InstallationApi(mvc, json, github);
        orgId = fixtures.newOrg();
        admin = member(orgId, "admin");
        installationId = fixtures.newInstallationId();
        api.as(admin);
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanUp();
        subjects.forEach(sub -> {
            sessions.delete(sub);
            jdbc.update("DELETE FROM git_integration.authorization_states WHERE user_id = ?", sub);
        });
    }

    @Test
    void aFreshInstallConsumesTheStateSavesTheSessionLinksAuditsAndSyncsAfterCommit() throws Exception {
        stubVisible();
        github.stubTokenMint(installationId);
        github.stubInstallationRepositories();

        MockHttpServletResponse response = api.linkFresh(orgId, installationId, api.installState(orgId));

        assertThat(response.getStatus()).isEqualTo(201);
        JsonNode link = api.data(response);
        assertThat(link.path("installationId").asLong()).isEqualTo(installationId);
        assertThat(link.path("accountLogin").asString()).isEqualTo("octo-org");
        assertThat(link.path("accountType").asString()).isEqualTo("Organization");
        assertThat(link.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(link.path("linkedByUserId").asString()).isEqualTo(admin);
        assertThat(linkRow(orgId))
                .containsEntry("status", "ACTIVE")
                .containsEntry("linked_by_user_id", admin)
                .containsEntry("linked_by_github_user_id", GITHUB_USER_ID);
        assertThat(installationRow())
                .containsEntry("status", "ACTIVE")
                .containsEntry("account_login", "octo-org")
                .containsEntry("unused_since", null);
        assertThat(audits(orgId, AuditEvents.INSTALLATION_LINKED)).isOne();
        assertThat(api.perform(get("/api/v1/git-integration/github/session")).getStatus())
                .isEqualTo(200);
        awaitSyncedRepositories(5);
    }

    @Test
    void aReplayedFreshInstallIsRejected() throws Exception {
        stubVisible();
        String state = api.installState(orgId);
        assertThat(api.linkFresh(orgId, installationId, state).getStatus()).isEqualTo(201);

        api.assertError(api.linkFresh(orgId, installationId, state), 400).hasErrorCode("INVALID_AUTHORIZATION_STATE");

        assertThat(github.requests(GitHubApiStub.OAUTH_TOKEN_PATH)).hasSize(1);
    }

    @Test
    void anExistingInstallationLinksWithASession() throws Exception {
        api.signIn();
        stubVisible();

        MockHttpServletResponse response = api.linkExisting(orgId, installationId);

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(linkRow(orgId)).containsEntry("status", "ACTIVE");
        assertThat(audits(orgId, AuditEvents.INSTALLATION_LINKED)).isOne();
    }

    @Test
    void anExistingInstallationWithoutASessionRequiresAuthorization() throws Exception {
        api.assertError(api.linkExisting(orgId, installationId), 403).hasErrorCode("GITHUB_AUTHORIZATION_REQUIRED");

        assertThat(linkCount()).isZero();
        assertThat(github.totalRequests()).isZero();
    }

    @Test
    void anInstallationOnTheLastPageOfTheCallersInstallationsLinks() throws Exception {
        api.signIn();
        github.stubUserInstallationPages(List.of(
                GitHubApiStub.fullPageOfOthers(1_000), GitHubApiStub.fullPageOfOthers(2_000), List.of(installationId)));
        github.stubInstallation(installationId);

        assertThat(api.linkExisting(orgId, installationId).getStatus()).isEqualTo(201);

        assertThat(github.requests(GitHubApiStub.USER_INSTALLATIONS_PATH)).hasSize(3);
    }

    @Test
    void anInstallationOnNoPageIsNotAccessibleAndGitHubIsNotAskedAboutIt() throws Exception {
        api.signIn();
        github.stubUserInstallationPages(
                List.of(GitHubApiStub.fullPageOfOthers(1_000), GitHubApiStub.fullPageOfOthers(2_000), List.of(3_000L)));
        github.stubInstallation(installationId);

        api.assertError(api.linkExisting(orgId, installationId), 403).hasErrorCode("INSTALLATION_NOT_ACCESSIBLE");

        assertThat(github.requests(GitHubApiStub.USER_INSTALLATIONS_PATH)).hasSize(3);
        assertThat(github.requests(GitHubApiStub.installationPath(installationId)))
                .isEmpty();
        assertThat(linkCount()).isZero();
        assertThat(installationCount()).isZero();
    }

    @Test
    void aSuspendedInstallationIsRefusedAndNothingIsLinked() throws Exception {
        api.signIn();
        github.stubUserInstallationPages(List.of(List.of(installationId)));
        github.stubSuspendedInstallation(installationId);

        api.assertError(api.linkExisting(orgId, installationId), 409).hasErrorCode("INSTALLATION_SUSPENDED");

        assertThat(linkCount()).isZero();
        assertThat(installationCount()).isZero();
    }

    @Test
    void relinkingAnUnlinkedRowFlipsItBackAndClearsUnusedSince() throws Exception {
        api.signIn();
        stubVisible();
        assertThat(api.linkExisting(orgId, installationId).getStatus()).isEqualTo(201);
        assertThat(api.unlink(orgId, installationId).getStatus()).isEqualTo(204);
        assertThat(linkRow(orgId)).containsEntry("status", "UNLINKED");
        assertThat(installationRow().get("unused_since")).isNotNull();

        assertThat(api.linkExisting(orgId, installationId).getStatus()).isEqualTo(201);

        assertThat(linkRow(orgId)).containsEntry("status", "ACTIVE").containsEntry("unlinked_at", null);
        assertThat(installationRow()).containsEntry("unused_since", null);
        assertThat(audits(orgId, AuditEvents.INSTALLATION_LINKED)).isEqualTo(2);
    }

    @Test
    void linkingAnAlreadyActiveInstallationIs200AndChangesNothing() throws Exception {
        api.signIn();
        stubVisible();
        assertThat(api.linkExisting(orgId, installationId).getStatus()).isEqualTo(201);
        Map<String, Object> before = linkRow(orgId);

        MockHttpServletResponse again = api.linkExisting(orgId, installationId);

        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(api.data(again).path("installationId").asLong()).isEqualTo(installationId);
        assertThat(linkRow(orgId)).isEqualTo(before);
        assertThat(audits(orgId, AuditEvents.INSTALLATION_LINKED)).isOne();
    }

    @Test
    void anInstallStateIssuedForOneOrgIsRejectedOnAnother() throws Exception {
        String otherOrg = fixtures.newOrg();
        fixtures.addMember(otherOrg, admin, "admin", "ACTIVE");
        stubVisible();
        String state = api.installState(orgId);

        api.assertError(api.linkFresh(otherOrg, installationId, state), 400)
                .hasErrorCode("INVALID_AUTHORIZATION_STATE");

        assertThat(github.requests(GitHubApiStub.OAUTH_TOKEN_PATH)).isEmpty();
        assertThat(linkCount()).isZero();
    }

    @Test
    void anInstallStateIssuedToOneUserIsRejectedForAnother() throws Exception {
        stubVisible();
        String state = api.installState(orgId);
        api.as(member(orgId, "owner"));

        api.assertError(api.linkFresh(orgId, installationId, state), 400).hasErrorCode("INVALID_AUTHORIZATION_STATE");

        assertThat(linkCount()).isZero();
    }

    @Test
    void aCodeWithoutAStateIsAValidationError() throws Exception {
        api.assertError(api.link(orgId, Map.of("installationId", installationId, "code", InstallationApi.CODE)), 400)
                .hasErrorCode("VALIDATION_ERROR");

        assertThat(github.totalRequests()).isZero();
    }

    private void stubVisible() {
        github.stubCodeExchange();
        github.stubCurrentUser();
        github.stubUserInstallationPages(List.of(List.of(installationId)));
        github.stubInstallation(installationId);
    }

    private String member(String org, String role) {
        String sub = fixtures.newMember(org, role, "ACTIVE");
        subjects.add(sub);
        return sub;
    }

    private void awaitSyncedRepositories(int expected) {
        await().atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.queryForObject(
                                "SELECT count(*) FROM git_integration.installation_repositories"
                                        + " WHERE installation_id = ?",
                                Integer.class,
                                installationId)
                        == expected);
    }

    private Map<String, Object> linkRow(String org) {
        return jdbc.queryForMap(
                "SELECT * FROM git_integration.installation_links WHERE installation_id = ? AND org_id = ?",
                installationId,
                org);
    }

    private Map<String, Object> installationRow() {
        return jdbc.queryForMap(
                "SELECT * FROM git_integration.installations WHERE installation_id = ?", installationId);
    }

    private int linkCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM git_integration.installation_links WHERE installation_id = ?",
                Integer.class,
                installationId);
    }

    private int installationCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM git_integration.installations WHERE installation_id = ?",
                Integer.class,
                installationId);
    }

    private int audits(String org, String action) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM git_integration.outbox_events
                 WHERE org_id = ? AND event_type = ? AND payload ->> 'action' = ?
                   AND payload ->> 'resource' = ?
                """, Integer.class, org, AuditEventRecorded.TYPE, action, "github-installation:" + installationId);
    }
}
