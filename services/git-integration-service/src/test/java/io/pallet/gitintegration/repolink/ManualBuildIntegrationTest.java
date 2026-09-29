package io.pallet.gitintegration.repolink;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.audit.AuditEvents;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.ReadModelFixtures;
import io.pallet.gitintegration.support.Tokens;
import io.pallet.gitintegration.support.TopicProbe;
import java.time.Instant;
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
class ManualBuildIntegrationTest {

    private static final long REPO = 7373;
    private static final String HEAD = "a".repeat(40);
    private static final String ACCEPTED_HEAD = "c".repeat(40);
    private static final String RELEASE_HEAD = "b".repeat(40);
    private static final int PER_HOUR = 30;

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
    private KafkaContainer kafka;

    private ReadModelFixtures fixtures;
    private RepoLinkApi api;
    private String orgId;
    private long installationId;
    private UUID appId;
    private String developer;

    @BeforeEach
    void setUp() {
        fixtures = new ReadModelFixtures(jdbc);
        api = new RepoLinkApi(mvc, json, github);
        orgId = fixtures.newOrg();
        installationId = fixtures.newInstallation();
        fixtures.linkInstallation(installationId, orgId, "ACTIVE");
        appId = fixtures.newRepoLink(orgId, installationId, REPO);
        developer = fixtures.newMember(orgId, "developer", "ACTIVE");
        jdbc.update("""
                INSERT INTO git_integration.branch_heads (app_id, branch, head_sha, advanced_at)
                VALUES (?, 'main', ?, now())
                """, appId, ACCEPTED_HEAD);
        github.stubTokenMint(installationId);
        github.stubBranch(REPO, "main", HEAD);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM git_integration.branch_heads WHERE app_id = ?", appId);
        fixtures.cleanUp();
    }

    @Test
    void aBuildOfTheProductionBranchHeadIsAcceptedAndPublishedOnce() throws Exception {
        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), GitPushReceived.TYPE)) {
            MockHttpServletResponse accepted = api.as(developer).build(orgId, appId, "k1", null);

            assertThat(accepted.getStatus()).as(accepted.getContentAsString()).isEqualTo(202);
            JsonNode result = api.data(accepted);
            assertThat(result.path("commitSha").asString()).isEqualTo(HEAD);
            assertThat(result.path("branch").asString()).isEqualTo("main");

            List<TopicProbe.Received> received = probe.awaitKey(orgId, 1);

            assertThat(received).hasSize(1);
            JsonNode push = json.readTree(received.getFirst().value());
            assertThat(push.path("eventId").asString())
                    .isEqualTo(result.path("eventId").asString());
            assertThat(push.path("trigger").asString()).isEqualTo(GitPushReceived.TRIGGER_MANUAL);
            assertThat(push.path("appId").asString()).isEqualTo(appId.toString());
            assertThat(push.path("commitSha").asString()).isEqualTo(HEAD);
            assertThat(push.path("beforeSha").asString()).isEqualTo(ACCEPTED_HEAD);
        }
        assertThat(audits())
                .singleElement()
                .satisfies(audit -> assertThat(
                                audit.path("context").path("commitSha").asString())
                        .isEqualTo(HEAD));
        assertThat(headSha()).isEqualTo(ACCEPTED_HEAD);
    }

    @Test
    void theSameKeyReplaysTheFirstResultWithoutGitHubOrASecondEvent() throws Exception {
        MockHttpServletResponse first = api.as(developer).build(orgId, appId, "k1", null);
        assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(202);
        github.stubBranch(REPO, "main", RELEASE_HEAD);

        MockHttpServletResponse replayed = api.build(orgId, appId, "k1", null);

        assertThat(replayed.getStatus()).isEqualTo(202);
        assertThat(replayed.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(github.requests(GitHubApiStub.branchPath(REPO, "main"))).hasSize(1);
        assertThat(pushCount()).isOne();
        assertThat(requestCount()).isOne();
        assertThat(headSha()).isEqualTo(ACCEPTED_HEAD);
    }

    @Test
    void theSameKeyForAnotherBranchIsAConflict() throws Exception {
        github.stubBranch(REPO, "release", RELEASE_HEAD);
        assertThat(api.as(developer).build(orgId, appId, "k1", null).getStatus())
                .isEqualTo(202);

        api.assertError(api.build(orgId, appId, "k1", Map.of("branch", "release")), 409)
                .hasErrorCode("IDEMPOTENCY_KEY_REUSE");

        assertThat(github.requests(GitHubApiStub.branchPath(REPO, "release"))).isEmpty();
        assertThat(pushCount()).isOne();
    }

    @Test
    void anotherBranchIsBuiltFromItsOwnHeadWithoutAHeadToChainFrom() throws Exception {
        github.stubBranch(REPO, "release", RELEASE_HEAD);

        MockHttpServletResponse accepted =
                api.as(developer).build(orgId, appId, "k-release", Map.of("branch", "release"));

        assertThat(accepted.getStatus()).as(accepted.getContentAsString()).isEqualTo(202);
        assertThat(api.data(accepted).path("branch").asString()).isEqualTo("release");
        assertThat(api.data(accepted).path("commitSha").asString()).isEqualTo(RELEASE_HEAD);
        assertThat(jdbc.queryForObject("""
                        SELECT payload ->> 'beforeSha' FROM git_integration.outbox_events
                         WHERE org_id = ? AND event_type = ?
                        """, String.class, orgId, GitPushReceived.TYPE))
                .isEqualTo("0".repeat(40));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM git_integration.branch_heads WHERE app_id = ?", Integer.class, appId))
                .isOne();
    }

    @Test
    void aMissingOrMalformedKeyOrBodyIsABadRequestBeforeGitHubIsAsked() throws Exception {
        api.as(developer);

        assertThat(api.build(orgId, appId, null, null).getStatus()).isEqualTo(400);
        assertThat(api.build(orgId, appId, "has space", null).getStatus()).isEqualTo(400);
        assertThat(api.build(orgId, appId, "k".repeat(129), null).getStatus()).isEqualTo(400);
        assertThat(api.build(orgId, appId, "k1", Map.of("branch", "a..b")).getStatus())
                .isEqualTo(400);
        assertThat(api.build(orgId, appId, "k1", Map.of("commitSha", HEAD)).getStatus())
                .isEqualTo(400);
        assertThat(api.build(orgId, appId, "k".repeat(128), null).getStatus()).isEqualTo(202);

        assertThat(github.requests(GitHubApiStub.branchPath(REPO, "main"))).hasSize(1);
    }

    @Test
    void theBuildAfterTheHourlyLimitIsRefusedUntilTheOldestLeavesTheWindow() throws Exception {
        seedBuilds(1, "50 minutes");
        seedBuilds(PER_HOUR - 1, "5 minutes");

        MockHttpServletResponse refused = api.as(developer).build(orgId, appId, "k-over", null);

        api.assertError(refused, 429).hasErrorCode("TOO_MANY_REQUESTS");
        long retryAfter = json.readTree(refused.getContentAsString())
                .path("meta")
                .path("retryAfter")
                .asLong();
        assertThat(retryAfter).isBetween(590L, 600L);
        assertThat(github.requests(GitHubApiStub.branchPath(REPO, "main"))).isEmpty();
        assertThat(pushCount()).isZero();
    }

    @Test
    void aReplayIsAnsweredEvenAtTheLimitAndBuildsOlderThanAnHourDontCount() throws Exception {
        MockHttpServletResponse first = api.as(developer).build(orgId, appId, "k1", null);
        assertThat(first.getStatus()).isEqualTo(202);
        seedBuilds(PER_HOUR - 1, "5 minutes");

        MockHttpServletResponse replayed = api.build(orgId, appId, "k1", null);
        assertThat(replayed.getContentAsString()).isEqualTo(first.getContentAsString());
        api.assertError(api.build(orgId, appId, "k2", null), 429).hasErrorCode("TOO_MANY_REQUESTS");

        jdbc.update(
                "UPDATE git_integration.manual_build_requests SET created_at = now() - interval '61 minutes'"
                        + " WHERE app_id = ?",
                appId);
        assertThat(api.build(orgId, appId, "k2", null).getStatus()).isEqualTo(202);
    }

    @Test
    void aViewerCantRequestABuild() throws Exception {
        String viewer = fixtures.newMember(orgId, "viewer", "ACTIVE");

        api.assertError(api.as(viewer).build(orgId, appId, "k1", null), 403).hasErrorCode("INSUFFICIENT_ROLE");

        assertThat(requestCount()).isZero();
    }

    @Test
    void aDisconnectedLinkOrAnotherOrgsAppIsNotFound() throws Exception {
        UUID foreignApp = fixtures.newApp(fixtures.newOrg(), "ACTIVE");
        api.assertError(api.as(developer).build(orgId, foreignApp, "k1", null), 404)
                .hasErrorCode("APP_NOT_FOUND");

        jdbc.update(
                "UPDATE git_integration.repo_links SET status = 'DISCONNECTED', disconnect_reason = 'UNLINKED_BY_USER',"
                        + " disconnected_at = now() WHERE app_id = ?",
                appId);
        api.assertError(api.build(orgId, appId, "k1", null), 404).hasErrorCode("REPO_LINK_NOT_FOUND");

        assertThat(github.allRequests()).isEmpty();
        assertThat(requestCount()).isZero();
    }

    @Test
    void aSuspendedInstallationIsAConflict() throws Exception {
        jdbc.update(
                "UPDATE git_integration.installations SET status = 'SUSPENDED' WHERE installation_id = ?",
                installationId);

        api.assertError(api.as(developer).build(orgId, appId, "k1", null), 409).hasErrorCode("INSTALLATION_SUSPENDED");

        assertThat(github.allRequests()).isEmpty();
    }

    @Test
    void aMissingBranchIsUnprocessableAndRecordsNothing() throws Exception {
        github.stubNotFound(GitHubApiStub.branchPath(REPO, "gone"));

        api.assertError(api.as(developer).build(orgId, appId, "k1", Map.of("branch", "gone")), 422)
                .hasErrorCode("BRANCH_NOT_FOUND");

        assertThat(requestCount()).isZero();
        assertThat(pushCount()).isZero();
        assertThat(headSha()).isEqualTo(ACCEPTED_HEAD);
    }

    @Test
    void aRateLimitedGitHubIsUnavailableAndTheKeyStaysUnused() throws Exception {
        github.stubRateLimited(
                GitHubApiStub.branchPath(REPO, "main"), Instant.now().plusSeconds(120));

        api.assertError(api.as(developer).build(orgId, appId, "k1", null), 503).hasErrorCode("GITHUB_RATE_LIMITED");

        assertThat(requestCount()).isZero();
        assertThat(pushCount()).isZero();
        assertThat(headSha()).isEqualTo(ACCEPTED_HEAD);
    }

    private void seedBuilds(int count, String age) {
        for (int i = 0; i < count; i++) {
            jdbc.update("""
                    INSERT INTO git_integration.manual_build_requests
                        (app_id, idempotency_key, org_id, request_hash, event_id, branch, commit_sha, requested_by,
                         created_at)
                    VALUES (?, ?, ?, ?, ?, 'main', ?, 'user-seed', now() - CAST(? AS interval))
                    """, appId, "seed-" + UUID.randomUUID(), orgId, "0".repeat(64), UUID.randomUUID(), HEAD, age);
        }
    }

    private String headSha() {
        return jdbc.queryForObject(
                "SELECT head_sha FROM git_integration.branch_heads WHERE app_id = ? AND branch = 'main'",
                String.class,
                appId);
    }

    private int pushCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM git_integration.outbox_events WHERE org_id = ? AND event_type = ?",
                Integer.class,
                orgId,
                GitPushReceived.TYPE);
    }

    private int requestCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM git_integration.manual_build_requests WHERE app_id = ?", Integer.class, appId);
    }

    private List<JsonNode> audits() {
        return jdbc
                .queryForList("""
                        SELECT payload::text FROM git_integration.outbox_events
                         WHERE org_id = ? AND event_type = ? AND payload ->> 'action' = ?
                        """, String.class, orgId, AuditEventRecorded.TYPE, AuditEvents.BUILD_REQUESTED)
                .stream()
                .map(json::readTree)
                .toList();
    }
}
