package io.pallet.gitintegration.repolink;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.audit.AuditEvents;
import io.pallet.gitintegration.session.GitHubUserSessionStore;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.ReadModelFixtures;
import io.pallet.gitintegration.support.Tokens;
import io.pallet.gitintegration.support.TopicProbe;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, Tokens.LocalDecoder.class})
class RepoLinkControllerIntegrationTest {

    private static final long REPO = 4242;
    private static final String HEAD = "a".repeat(40);
    private static final String RELEASE_HEAD = "b".repeat(40);

    /** The GitHub user {@code github/api/user.json} signs every session in as. */
    private static final String GITHUB_LOGIN = "fixture-dev";

    private static final long GITHUB_USER_ID = 9100001;

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

    @Autowired
    private KafkaContainer kafka;

    private ReadModelFixtures fixtures;
    private RepoLinkApi api;
    private final List<String> signedIn = new ArrayList<>();
    private String orgId;
    private String admin;
    private long installationId;
    private UUID appId;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new ReadModelFixtures(jdbc);
        api = new RepoLinkApi(mvc, json, github);
        orgId = fixtures.newOrg();
        installationId = fixtures.newInstallation();
        fixtures.linkInstallation(installationId, orgId, "ACTIVE");
        appId = fixtures.newApp(orgId, "ACTIVE");
        admin = signedIn(orgId, "admin");
        github.stubRepository(REPO, installationId, "push", false);
        github.stubTokenMint(installationId);
        github.stubBranch(REPO, "main", HEAD);
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanUp();
        signedIn.forEach(sub -> {
            sessions.delete(sub);
            jdbc.update("DELETE FROM git_integration.authorization_states WHERE user_id = ?", sub);
        });
    }

    @Test
    void anAdminLinksARepositoryTheyCanPushToAndNothingIsBuiltYet() throws Exception {
        MockHttpServletResponse linked = api.as(admin).link(orgId, appId, installationId, REPO);

        assertThat(linked.getStatus()).as(linked.getContentAsString()).isEqualTo(201);
        JsonNode link = api.data(linked);
        assertThat(link.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(link.path("productionBranch").asString()).isEqualTo("main");
        assertThat(link.path("autoDeploy").asBoolean()).isTrue();
        assertThat(link.path("lastAcceptedHead").path("sha").asString()).isEqualTo(HEAD);
        assertThat(link.path("verification").path("githubLogin").asString()).isEqualTo(GITHUB_LOGIN);
        assertThat(row())
                .containsEntry("status", "ACTIVE")
                .containsEntry("verified_by_user_id", admin)
                .containsEntry("verified_github_user_id", GITHUB_USER_ID)
                .containsEntry("verified_github_login", GITHUB_LOGIN)
                .containsEntry("verified_permission", "push")
                .containsEntry("repo_full_name", "octo-org/repo-" + REPO)
                .containsEntry("installation_id", installationId);
        assertThat(heads()).containsExactly(Map.of("branch", "main", "head_sha", HEAD));
        assertThat(audits(AuditEvents.REPO_LINK_CREATED)).hasSize(1);
        assertThat(outboxCount(GitPushReceived.TYPE)).isZero();
        assertThat(github.mintRequests(installationId))
                .singleElement()
                .satisfies(mint -> assertThat(json.readTree(mint.getBodyAsString()))
                        .isEqualTo(json.readTree(
                                "{\"repository_ids\":[" + REPO + "]," + "\"permissions\":{\"contents\":\"read\"}}")));
    }

    @Test
    void deployNowPublishesExactlyOneLinkedPushKeyedByTheOrg() throws Exception {
        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), GitPushReceived.TYPE)) {
            MockHttpServletResponse linked = api.as(admin)
                    .link(
                            orgId,
                            appId,
                            Map.of(
                                    "installationId",
                                    installationId,
                                    "repoId",
                                    REPO,
                                    "rootDirectory",
                                    "apps/web/",
                                    "deployNow",
                                    true));
            assertThat(linked.getStatus()).as(linked.getContentAsString()).isEqualTo(201);

            List<TopicProbe.Received> received = probe.awaitKey(orgId, 1);

            assertThat(received).hasSize(1);
            JsonNode push = json.readTree(received.getFirst().value());
            assertThat(push.path("trigger").asString()).isEqualTo(GitPushReceived.TRIGGER_LINKED);
            assertThat(push.path("orgId").asString()).isEqualTo(orgId);
            assertThat(push.path("appId").asString()).isEqualTo(appId.toString());
            assertThat(push.path("installationId").asLong()).isEqualTo(installationId);
            assertThat(push.path("repoId").asLong()).isEqualTo(REPO);
            assertThat(push.path("rootDirectory").asString()).isEqualTo("apps/web");
            assertThat(push.path("branch").asString()).isEqualTo("main");
            assertThat(push.path("commitSha").asString()).isEqualTo(HEAD);
        }
        assertThat(outboxCount(GitPushReceived.TYPE)).isOne();
    }

    @Test
    void anotherOrgSharingTheInstallationCantLinkAPrivateRepositoryItsAdminCanOnlyRead() throws Exception {
        String orgB = fixtures.newOrg();
        fixtures.linkInstallation(installationId, orgB, "ACTIVE");
        UUID appB = fixtures.newApp(orgB, "ACTIVE");
        String adminB = signedIn(orgB, "admin");
        String repositoryName = "repo-" + REPO;

        github.stubRepository(REPO, installationId, "pull", false);
        MockHttpServletResponse readOnly = api.as(adminB).link(orgB, appB, installationId, REPO);
        api.assertError(readOnly, 403).hasErrorCode("REPOSITORY_PERMISSION_TOO_LOW");
        assertThat(readOnly.getContentAsString()).doesNotContain(repositoryName);

        github.stubNotFound(GitHubApiStub.repositoryPath(REPO));
        MockHttpServletResponse noAccess = api.link(orgB, appB, installationId, REPO);
        api.assertError(noAccess, 403).hasErrorCode("REPOSITORY_NOT_ACCESSIBLE");
        assertThat(noAccess.getContentAsString()).doesNotContain(repositoryName);

        assertThat(repoLinkCount(appB)).isZero();
        assertThat(github.verifyTokenMints(installationId)).isZero();
    }

    @Test
    void anArchivedRepositoryOrAMissingBranchIsUnprocessable() throws Exception {
        github.stubRepository(REPO, installationId, "admin", true);
        api.assertError(api.as(admin).link(orgId, appId, installationId, REPO), 422)
                .hasErrorCode("REPOSITORY_ARCHIVED");

        github.stubRepository(REPO, installationId, "admin", false);
        github.stubNotFound(GitHubApiStub.branchPath(REPO, "gone"));
        api.assertError(
                        api.link(
                                orgId,
                                appId,
                                Map.of("installationId", installationId, "repoId", REPO, "productionBranch", "gone")),
                        422)
                .hasErrorCode("BRANCH_NOT_FOUND");

        assertThat(repoLinkCount(appId)).isZero();
    }

    @Test
    void aLinkToARepositoryArchivedAfterLinkingStaysAndCarriesAWarning() throws Exception {
        assertThat(api.as(admin).link(orgId, appId, installationId, REPO).getStatus())
                .isEqualTo(201);
        assertThat(api.data(api.get(orgId, appId)).path("warnings").isEmpty()).isTrue();
        jdbc.update("""
                INSERT INTO git_integration.installation_repositories
                    (installation_id, repo_id, full_name, default_branch, is_private, archived)
                VALUES (?, ?, 'octo-org/api', 'main', true, true)
                """, installationId, REPO);

        JsonNode link = api.data(api.get(orgId, appId));

        assertThat(link.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(link.path("warnings").get(0).asString()).isEqualTo("REPOSITORY_ARCHIVED");
    }

    @Test
    void anInvalidRootDirectoryOrBranchIsABadRequestBeforeGitHubIsAsked() throws Exception {
        api.assertError(
                        api.as(admin)
                                .link(
                                        orgId,
                                        appId,
                                        Map.of(
                                                "installationId",
                                                installationId,
                                                "repoId",
                                                REPO,
                                                "rootDirectory",
                                                "apps/../../etc")),
                        400)
                .hasErrorCode("INVALID_ROOT_DIRECTORY");
        assertThat(api.link(
                                orgId,
                                appId,
                                Map.of("installationId", installationId, "repoId", REPO, "productionBranch", "a..b"))
                        .getStatus())
                .isEqualTo(400);

        assertThat(github.requests(GitHubApiStub.repositoryPath(REPO))).isEmpty();
    }

    @Test
    void anInstallationThisOrgHasNotLinkedIsNotFoundAndASuspendedOneIsAConflict() throws Exception {
        long othersInstallation = fixtures.newInstallation();
        fixtures.linkInstallation(othersInstallation, fixtures.newOrg(), "ACTIVE");
        api.assertError(api.as(admin).link(orgId, appId, othersInstallation, REPO), 404)
                .hasErrorCode("INSTALLATION_NOT_FOUND");

        jdbc.update(
                "UPDATE git_integration.installations SET status = 'SUSPENDED' WHERE installation_id = ?",
                installationId);
        api.assertError(api.link(orgId, appId, installationId, REPO), 409).hasErrorCode("INSTALLATION_SUSPENDED");

        assertThat(github.requests(GitHubApiStub.repositoryPath(REPO))).isEmpty();
    }

    @Test
    void linkingNeedsASessionAndAnAdmin() throws Exception {
        String unsignedAdmin = fixtures.newMember(orgId, "owner", "ACTIVE");
        api.assertError(api.as(unsignedAdmin).link(orgId, appId, installationId, REPO), 403)
                .hasErrorCode("GITHUB_AUTHORIZATION_REQUIRED");

        String developer = signedIn(orgId, "developer");
        api.assertError(api.as(developer).link(orgId, appId, installationId, REPO), 403)
                .hasErrorCode("INSUFFICIENT_ROLE");

        assertThat(repoLinkCount(appId)).isZero();
    }

    @Test
    void anAppFromAnotherOrgIsNotFound() throws Exception {
        UUID foreignApp = fixtures.newApp(fixtures.newOrg(), "ACTIVE");

        api.assertError(api.as(admin).link(orgId, foreignApp, installationId, REPO), 404)
                .hasErrorCode("APP_NOT_FOUND");
        api.assertError(api.get(orgId, foreignApp), 404).hasErrorCode("APP_NOT_FOUND");
    }

    @Test
    void aSecondLinkForTheSameAppIsAConflict() throws Exception {
        assertThat(api.as(admin).link(orgId, appId, installationId, REPO).getStatus())
                .isEqualTo(201);

        api.assertError(api.link(orgId, appId, installationId, REPO), 409).hasErrorCode("REPO_LINK_EXISTS");
    }

    @Test
    void anyMemberReadsTheLinkAndAnAppWithoutOneIsNotFound() throws Exception {
        String viewer = fixtures.newMember(orgId, "viewer", "ACTIVE");
        api.assertError(api.as(viewer).get(orgId, appId), 404).hasErrorCode("REPO_LINK_NOT_FOUND");

        assertThat(api.as(admin).link(orgId, appId, installationId, REPO).getStatus())
                .isEqualTo(201);
        MockHttpServletResponse read = api.as(viewer).get(orgId, appId);

        assertThat(read.getStatus()).isEqualTo(200);
        JsonNode link = api.data(read);
        assertThat(link.path("repoId").asLong()).isEqualTo(REPO);
        assertThat(link.path("lastAcceptedHead").path("branch").asString()).isEqualTo("main");
        assertThat(link.path("verification").path("permission").asString()).isEqualTo("push");
        assertThat(link.path("verification").path("accessCheckedAt").isMissingNode())
                .isFalse();
    }

    @Test
    void aDeveloperMovesTheBranchWhichDropsTheOldHead() throws Exception {
        assertThat(api.as(admin).link(orgId, appId, installationId, REPO).getStatus())
                .isEqualTo(201);
        long version = version();
        github.stubBranch(REPO, "release", RELEASE_HEAD);
        String developer = fixtures.newMember(orgId, "developer", "ACTIVE");

        MockHttpServletResponse updated = api.as(developer)
                .update(orgId, appId, Map.of("productionBranch", "release", "autoDeploy", false, "version", version));

        assertThat(updated.getStatus()).as(updated.getContentAsString()).isEqualTo(200);
        assertThat(row())
                .containsEntry("production_branch", "release")
                .containsEntry("auto_deploy", false)
                .containsEntry("verified_by_user_id", admin)
                .containsEntry("version", version + 1);
        assertThat(heads()).isEmpty();
        assertThat(api.data(updated).path("lastAcceptedHead").path("sha").isMissingNode())
                .isTrue();
        assertThat(audits(AuditEvents.REPO_LINK_UPDATED))
                .singleElement()
                .satisfies(audit -> assertThat(audit.path("context").path("changedFields"))
                        .isEqualTo(json.readTree("[\"productionBranch\",\"autoDeploy\"]")));
    }

    @Test
    void aPatchCantNameAnotherRepositoryAndLosesToAConcurrentChange() throws Exception {
        assertThat(api.as(admin).link(orgId, appId, installationId, REPO).getStatus())
                .isEqualTo(201);
        long version = version();
        String developer = fixtures.newMember(orgId, "developer", "ACTIVE");

        assertThat(api.as(developer)
                        .update(orgId, appId, Map.of("repoId", 99, "autoDeploy", false, "version", version))
                        .getStatus())
                .isEqualTo(400);
        assertThat(api.update(orgId, appId, Map.of("installationId", 99, "version", version))
                        .getStatus())
                .isEqualTo(400);
        assertThat(api.update(orgId, appId, Map.of("rootDirectory", "apps/web", "version", version))
                        .getStatus())
                .isEqualTo(200);
        api.assertError(api.update(orgId, appId, Map.of("autoDeploy", false, "version", version)), 409)
                .hasErrorCode("CONCURRENT_MODIFICATION");

        assertThat(row())
                .containsEntry("repo_id", REPO)
                .containsEntry("installation_id", installationId)
                .containsEntry("root_directory", "apps/web")
                .containsEntry("auto_deploy", true);
    }

    @Test
    void anAdminWithMaintainTakesOverVerification() throws Exception {
        assertThat(api.as(admin).link(orgId, appId, installationId, REPO).getStatus())
                .isEqualTo(201);
        String successor = signedIn(orgId, "admin");
        github.stubRepository(REPO, installationId, "maintain", false);

        MockHttpServletResponse verified = api.as(successor).takeOverVerification(orgId, appId);

        assertThat(verified.getStatus()).as(verified.getContentAsString()).isEqualTo(200);
        assertThat(row())
                .containsEntry("verified_by_user_id", successor)
                .containsEntry("verified_permission", "maintain");
        assertThat(audits(AuditEvents.REPO_LINK_VERIFIER_CHANGED)).hasSize(1);
    }

    @Test
    void anAdminWithOnlyTriageCantTakeOverAndTheVerifierStays() throws Exception {
        assertThat(api.as(admin).link(orgId, appId, installationId, REPO).getStatus())
                .isEqualTo(201);
        String candidate = signedIn(orgId, "admin");
        github.stubRepository(REPO, installationId, "triage", false);

        api.assertError(api.as(candidate).takeOverVerification(orgId, appId), 403)
                .hasErrorCode("REPOSITORY_PERMISSION_TOO_LOW");

        assertThat(row()).containsEntry("verified_by_user_id", admin).containsEntry("verified_permission", "push");
        assertThat(audits(AuditEvents.REPO_LINK_VERIFIER_CHANGED)).isEmpty();
    }

    @Test
    void disconnectingThenLinkingAgainReusesTheRow() throws Exception {
        assertThat(api.as(admin).link(orgId, appId, installationId, REPO).getStatus())
                .isEqualTo(201);
        long linkedVersion = version();

        assertThat(api.disconnect(orgId, appId).getStatus()).isEqualTo(204);

        assertThat(row())
                .containsEntry("status", "DISCONNECTED")
                .containsEntry("disconnect_reason", "UNLINKED_BY_USER");
        assertThat(row().get("disconnected_at")).isNotNull();
        assertThat(heads()).isEmpty();
        assertThat(audits(AuditEvents.REPO_LINK_DISCONNECTED)).hasSize(1);
        api.assertError(api.disconnect(orgId, appId), 404).hasErrorCode("REPO_LINK_NOT_FOUND");

        assertThat(api.link(orgId, appId, installationId, REPO).getStatus()).isEqualTo(201);

        assertThat(repoLinkCount(appId)).isOne();
        assertThat(row())
                .containsEntry("status", "ACTIVE")
                .containsEntry("disconnect_reason", null)
                .containsEntry("disconnected_at", null);
        assertThat(version()).isGreaterThan(linkedVersion);
        assertThat(heads()).hasSize(1);
    }

    @Test
    void anUnlinkCommittedWhileGitHubIsCheckedFailsTheLinkCleanly() throws Exception {
        github.beforeAnswering(GitHubApiStub.branchPath(REPO, "main"), () -> jdbc.update("""
                        UPDATE git_integration.installation_links SET status = 'UNLINKED', unlinked_at = now()
                         WHERE installation_id = ? AND org_id = ?
                        """, installationId, orgId));

        api.assertError(api.as(admin).link(orgId, appId, installationId, REPO), 404)
                .hasErrorCode("INSTALLATION_NOT_FOUND");

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM git_integration.repo_links WHERE app_id = ? AND status = 'ACTIVE'",
                        Integer.class,
                        appId))
                .isZero();
        assertThat(audits(AuditEvents.REPO_LINK_CREATED)).isEmpty();
    }

    private String signedIn(String org, String role) throws Exception {
        String sub = fixtures.newMember(org, role, "ACTIVE");
        signedIn.add(sub);
        api.as(sub).signIn();
        return sub;
    }

    private Map<String, Object> row() {
        return new HashMap<>(jdbc.queryForMap("SELECT * FROM git_integration.repo_links WHERE app_id = ?", appId));
    }

    private long version() {
        return jdbc.queryForObject(
                "SELECT version FROM git_integration.repo_links WHERE app_id = ?", Long.class, appId);
    }

    private int repoLinkCount(UUID app) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM git_integration.repo_links WHERE app_id = ?", Integer.class, app);
    }

    private List<Map<String, Object>> heads() {
        return jdbc.queryForList("SELECT branch, head_sha FROM git_integration.branch_heads WHERE app_id = ?", appId);
    }

    private int outboxCount(String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM git_integration.outbox_events WHERE org_id = ? AND event_type = ?",
                Integer.class,
                orgId,
                eventType);
    }

    private List<JsonNode> audits(String action) {
        return jdbc.queryForList("""
                        SELECT payload::text FROM git_integration.outbox_events
                         WHERE org_id = ? AND event_type = ? AND payload ->> 'action' = ?
                        """, String.class, orgId, AuditEventRecorded.TYPE, action).stream()
                .map(json::readTree)
                .toList();
    }
}
