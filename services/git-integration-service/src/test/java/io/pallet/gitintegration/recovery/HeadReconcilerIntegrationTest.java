package io.pallet.gitintegration.recovery;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.pallet.gitintegration.recovery.RecoveryScenario.sha;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.delivery.DeliveryProcessor;
import io.pallet.gitintegration.observability.MetricsCatalog;
import io.pallet.gitintegration.push.PushProcessor;
import io.pallet.gitintegration.recovery.HeadReconciler.Run;
import io.pallet.gitintegration.recovery.RecoveryMetrics.Checked;
import io.pallet.gitintegration.support.GitHubApiStub;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, GitHubApiStub.Properties.class})
class HeadReconcilerIntegrationTest {

    private static final String A = sha('a');
    private static final String B = sha('b');
    private static final String C = sha('c');
    private static final String ETAG = "\"branch-etag-1\"";

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @Autowired
    private HeadReconciler reconciler;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeliveryProcessor processor;

    @Autowired
    private MeterRegistry meters;

    private RecoveryScenario scenario;
    private long installationId;
    private long repoId;

    @BeforeEach
    void setUp() {
        scenario = new RecoveryScenario(jdbc, mvc, processor);
        installationId = scenario.fixtures.newInstallation();
        repoId = RecoveryScenario.newRepoId();
        github.stubTokenMint(installationId);
    }

    @AfterEach
    void tearDown() {
        scenario.cleanUp();
    }

    @Test
    void aHeadThatMovedForwardIsBuiltOnceAndTheNextRunIsNotModified() {
        String orgId = scenario.orgOn(installationId);
        UUID appId = scenario.linkApp(orgId, installationId, repoId, A);
        github.stubBranch(repoId, "main", B, ETAG);
        github.stubCompare(repoId, A, B, "ahead");

        Run first = reconciler.run().orElseThrow();

        assertThat(first.pushesFound()).isEqualTo(1);
        assertThat(first.checked()).containsEntry(Checked.CHANGED, 1);
        assertThat(scenario.published(orgId)).singleElement().satisfies(event -> {
            assertThat(event.path("trigger").asString()).isEqualTo(GitPushReceived.TRIGGER_RECONCILED);
            assertThat(event.path("beforeSha").asString()).isEqualTo(A);
            assertThat(event.path("commitSha").asString()).isEqualTo(B);
            assertThat(event.path("appId").asString()).isEqualTo(appId.toString());
        });
        assertThat(scenario.head(appId)).contains(B);
        assertThat(scenario.etag(appId)).contains(ETAG);

        double notModifiedBefore = checkedCount(Checked.NOT_MODIFIED);
        Run second = reconciler.run().orElseThrow();

        assertThat(second.checked()).containsOnly(Map.entry(Checked.NOT_MODIFIED, 1));
        assertThat(checkedCount(Checked.NOT_MODIFIED) - notModifiedBefore).isEqualTo(1);
        assertThat(second.pushesFound()).isZero();
        assertThat(scenario.published(orgId)).hasSize(1);
        assertThat(github.requests(GitHubApiStub.branchPath(repoId, "main")))
                .last()
                .satisfies(request ->
                        assertThat(request.getHeader("If-None-Match")).isEqualTo(ETAG));
    }

    @Test
    void oneFetchAndOneCompareServeEveryOrgLinkingTheRepository() {
        List<String> orgs =
                List.of(scenario.orgOn(installationId), scenario.orgOn(installationId), scenario.orgOn(installationId));
        orgs.forEach(orgId -> scenario.linkApp(orgId, installationId, repoId, A));
        github.stubBranch(repoId, "main", B);
        github.stubCompare(repoId, A, B, "ahead");

        Run run = reconciler.run().orElseThrow();

        assertThat(run.pushesFound()).isEqualTo(3);
        assertThat(orgs)
                .allSatisfy(orgId -> assertThat(scenario.published(orgId))
                        .singleElement()
                        .satisfies(event ->
                                assertThat(event.path("commitSha").asString()).isEqualTo(B)));
        assertThat(github.requests(GitHubApiStub.branchPath(repoId, "main"))).hasSize(1);
        assertThat(github.requests(GitHubApiStub.comparePath(repoId, A, B))).hasSize(1);
    }

    @Test
    void theBranchIsReadConditionallyOnlyWhileEveryLinkedHeadSharesOneEtag() {
        UUID first = scenario.linkApp(scenario.orgOn(installationId), installationId, repoId, null);
        UUID second = scenario.linkApp(scenario.orgOn(installationId), installationId, repoId, null);
        scenario.head(first, A, ETAG);
        scenario.head(second, A, ETAG);
        github.stubBranch(repoId, "main", A, ETAG);

        Run shared = reconciler.run().orElseThrow();

        assertThat(shared.checked()).containsOnly(Map.entry(Checked.NOT_MODIFIED, 1));

        UUID headless = scenario.linkApp(scenario.orgOn(installationId), installationId, repoId, null);
        github.server().resetRequests();
        reconciler.run().orElseThrow();

        assertThat(github.requests(GitHubApiStub.branchPath(repoId, "main")))
                .singleElement()
                .satisfies(request ->
                        assertThat(request.containsHeader("If-None-Match")).isFalse());
        assertThat(scenario.head(headless)).contains(A);
        assertThat(scenario.etag(headless)).contains(ETAG);
    }

    @Test
    void aGitHubHeadBehindOursBuildsNothing() {
        String orgId = scenario.orgOn(installationId);
        UUID appId = scenario.linkApp(orgId, installationId, repoId, C);
        github.stubBranch(repoId, "main", B);
        github.stubCompare(repoId, C, B, "behind");

        Run run = reconciler.run().orElseThrow();

        assertThat(run.pushesFound()).isZero();
        assertThat(run.checked()).containsEntry(Checked.UNCHANGED, 1);
        assertThat(scenario.published(orgId)).isEmpty();
        assertThat(scenario.head(appId)).contains(C);
    }

    @Test
    void aBranchWithNoHeadAdoptsGitHubsWithoutBuilding() {
        String orgId = scenario.orgOn(installationId);
        UUID appId = scenario.linkApp(orgId, installationId, repoId, null);
        github.stubBranch(repoId, "main", B, ETAG);

        Run run = reconciler.run().orElseThrow();

        assertThat(run.pushesFound()).isZero();
        assertThat(scenario.published(orgId)).isEmpty();
        assertThat(scenario.head(appId)).contains(B);
        assertThat(scenario.etag(appId)).contains(ETAG);
        assertThat(github.requests(GitHubApiStub.comparePath(repoId, A, B))).isEmpty();
    }

    @Test
    void aPushDeliveredAfterTheReconcilerAcceptedItsCommitIsADuplicate() {
        String orgId = scenario.orgOn(installationId);
        UUID appId = scenario.linkApp(orgId, installationId, repoId, A);
        github.stubBranch(repoId, "main", B);
        github.stubCompare(repoId, A, B, "ahead");
        reconciler.run().orElseThrow();

        Map<String, Object> late = scenario.postAndProcess(scenario.push(installationId, repoId, A, B));

        assertThat(late).containsEntry("status", "IGNORED").containsEntry("outcome_reason", PushProcessor.DUPLICATE);
        assertThat(scenario.published(orgId)).hasSize(1);
        assertThat(scenario.head(appId)).contains(B);
    }

    @Test
    void aPushThatAdvancedTheHeadClearsItsEtagSoTheNextReadIsUnconditional() {
        String orgId = scenario.orgOn(installationId);
        UUID appId = scenario.linkApp(orgId, installationId, repoId, null);
        github.stubBranch(repoId, "main", A, ETAG);
        reconciler.run().orElseThrow();

        scenario.postAndProcess(scenario.push(installationId, repoId, A, B));

        assertThat(scenario.head(appId)).contains(B);
        assertThat(scenario.etag(appId)).isEmpty();
    }

    @Test
    void anInstallationUnderItsBudgetReserveIsSkippedWhileAnotherProceeds() {
        String lowOrg = scenario.orgOn(installationId);
        UUID lowApp = scenario.linkApp(lowOrg, installationId, repoId, B);
        long otherInstallation = scenario.fixtures.newInstallation();
        long otherRepo = RecoveryScenario.newRepoId();
        github.stubTokenMint(otherInstallation);
        String otherOrg = scenario.orgOn(otherInstallation);
        scenario.linkApp(otherOrg, otherInstallation, otherRepo, A);
        github.server()
                .stubFor(get(urlPathEqualTo(GitHubApiStub.branchPath(repoId, "main")))
                        .willReturn(GitHubApiStub.json("{\"name\":\"main\",\"commit\":{\"sha\":\"" + B + "\"}}")
                                .withHeader("x-ratelimit-remaining", "100")
                                .withHeader(
                                        "x-ratelimit-reset",
                                        String.valueOf(
                                                Instant.now().plusSeconds(3_600).getEpochSecond()))));
        github.stubBranch(otherRepo, "main", A);
        reconciler.run().orElseThrow();
        github.server().resetRequests();
        github.stubBranch(otherRepo, "main", B);
        github.stubCompare(otherRepo, A, B, "ahead");

        reconciler.run().orElseThrow();

        assertThat(github.requests(GitHubApiStub.branchPath(repoId, "main"))).isEmpty();
        assertThat(github.requests(GitHubApiStub.branchPath(otherRepo, "main"))).hasSize(1);
        assertThat(scenario.published(otherOrg))
                .extracting(event -> event.path("commitSha").asString())
                .containsExactly(B);
        assertThat(scenario.head(lowApp)).contains(B);
    }

    @Test
    void gitHubFailingOnAnInstallationsFirstBranchLeavesTheCursorBeforeTheInstallation() {
        String orgId = scenario.orgOn(installationId);
        scenario.linkApp(orgId, installationId, repoId, A);
        String before = new ReconcileBatchPlanner.Cursor(installationId - 1, 0).format();
        jdbc.update(
                "INSERT INTO git_integration.sync_cursors (name, cursor) VALUES (?, ?)", HeadReconciler.CURSOR, before);
        github.stub5xx(GitHubApiStub.branchPath(repoId, "main"));

        Run failed = reconciler.run().orElseThrow();

        assertThat(failed.pushesFound()).isZero();
        assertThat(failed.cursor().format()).isEqualTo(before);
        assertThat(jdbc.queryForObject(
                        "SELECT cursor FROM git_integration.sync_cursors WHERE name = ?",
                        String.class,
                        HeadReconciler.CURSOR))
                .isEqualTo(before);

        github.stubBranch(repoId, "main", B);
        github.stubCompare(repoId, A, B, "ahead");
        Run retried = reconciler.run().orElseThrow();

        assertThat(retried.pushesFound()).isEqualTo(1);
        assertThat(scenario.published(orgId)).hasSize(1);
    }

    @Test
    void aLinkThatNoLongerAutoDeploysIsNotChecked() {
        String orgId = scenario.orgOn(installationId);
        UUID appId = scenario.linkApp(orgId, installationId, repoId, A);
        jdbc.update("UPDATE git_integration.repo_links SET auto_deploy = false WHERE app_id = ?", appId);
        github.stubBranch(repoId, "main", B);

        reconciler.run().orElseThrow();

        assertThat(github.requests(GitHubApiStub.branchPath(repoId, "main"))).isEmpty();
        assertThat(scenario.published(orgId)).isEmpty();
    }

    private double checkedCount(Checked result) {
        return meters.counter(
                        MetricsCatalog.RECONCILER_CHECKED,
                        "result",
                        result.name().toLowerCase(Locale.ROOT))
                .count();
    }
}
