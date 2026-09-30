package io.pallet.gitintegration.push;

import static io.pallet.gitintegration.push.PushScenario.sha;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.delivery.DeliveryProcessor;
import io.pallet.gitintegration.observability.MetricsCatalog;
import io.pallet.gitintegration.push.PushScenario.Published;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.WebhookFixtures;
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
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, GitHubApiStub.Properties.class})
class PushProcessingIntegrationTest {

    private static final String HEAD = sha('a');
    private static final String NEXT = sha('b');

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeliveryProcessor processor;

    @Autowired
    private MeterRegistry meters;

    private PushScenario scenario;
    private String orgId;
    private UUID appId;

    @BeforeEach
    void setUp() {
        scenario = new PushScenario(mvc, jdbc, processor);
        orgId = scenario.newOrg();
        appId = scenario.linkApp(orgId);
    }

    @AfterEach
    void tearDown() {
        scenario.cleanUp();
    }

    @Test
    void theFirstPushAfterLinkingPublishesOneEventAndBecomesTheHead() {
        scenario.rootDirectory(appId, "src/main");
        double publishedBefore = counter(MetricsCatalog.PUSHES_PUBLISHED, "trigger", GitPushReceived.TRIGGER_WEBHOOK);
        UUID delivery = scenario.post(scenario.push(HEAD, NEXT));

        Map<String, Object> row = scenario.process(delivery);

        assertThat(row).containsEntry("status", "PROCESSED").containsEntry("attempts", 0);
        assertThat(scenario.head(appId)).contains(NEXT);
        List<Published> events = scenario.published(orgId);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isEqualTo(PushEventFactory.eventId(delivery, appId));
            assertThat(event.recordKey()).isNull();
            assertThat(event.appId()).isEqualTo(appId.toString());
            assertThat(event.commitSha()).isEqualTo(NEXT);
            assertThat(event.payload().path("beforeSha").asString()).isEqualTo(HEAD);
            assertThat(event.payload().path("branch").asString()).isEqualTo("main");
            assertThat(event.payload().path("trigger").asString()).isEqualTo(GitPushReceived.TRIGGER_WEBHOOK);
            assertThat(event.payload().path("deliveryId").asString()).isEqualTo(delivery.toString());
            assertThat(event.payload().path("rootDirectory").asString()).isEqualTo("src/main");
            assertThat(event.payload().path("installationId").asLong()).isEqualTo(scenario.installationId);
            assertThat(event.payload().path("repoId").asLong()).isEqualTo(scenario.repoId);
            assertThat(event.payload().path("headCommitMessage").asString()).isEqualTo("Tune startup probe");
        });
        assertThat(counter(MetricsCatalog.PUSHES_PUBLISHED, "trigger", GitPushReceived.TRIGGER_WEBHOOK))
                .isEqualTo(publishedBefore + 1);
        assertThat(github.totalRequests()).isZero();
    }

    @Test
    void aFastForwardIsAcceptedWithoutCallingGitHub() {
        scenario.head(appId, HEAD);

        Map<String, Object> row = scenario.postAndProcess(scenario.push(HEAD, NEXT));

        assertThat(row).containsEntry("status", "PROCESSED");
        assertThat(scenario.head(appId)).contains(NEXT);
        assertThat(scenario.published(orgId)).extracting(Published::commitSha).containsExactly(NEXT);
        assertThat(github.totalRequests()).isZero();
    }

    @Test
    void theSamePushUnderANewGuidIsADuplicate() {
        scenario.head(appId, HEAD);
        scenario.postAndProcess(scenario.push(HEAD, NEXT));
        double duplicatesBefore = counter(MetricsCatalog.CHAIN_RULE_OUTCOME, "outcome", "duplicate");

        Map<String, Object> redelivered = scenario.postAndProcess(scenario.push(HEAD, NEXT));

        assertThat(redelivered)
                .containsEntry("status", "IGNORED")
                .containsEntry("outcome_reason", PushProcessor.DUPLICATE);
        assertThat(scenario.published(orgId)).hasSize(1);
        assertThat(counter(MetricsCatalog.CHAIN_RULE_OUTCOME, "outcome", "duplicate"))
                .isEqualTo(duplicatesBefore + 1);
        assertThat(github.totalRequests()).isZero();
    }

    @Test
    void aTagIsIgnoredAndCountedOnce() {
        double skippedBefore = counter(MetricsCatalog.PUSHES_SKIPPED, "reason", SkipRules.NOT_A_BRANCH);

        Map<String, Object> row = scenario.postAndProcess(WebhookFixtures.delivery("push-tag.json")
                .with("/installation/id", scenario.installationId)
                .with("/repository/id", scenario.repoId));

        assertIgnored(row, SkipRules.NOT_A_BRANCH);
        assertThat(counter(MetricsCatalog.PUSHES_SKIPPED, "reason", SkipRules.NOT_A_BRANCH))
                .isEqualTo(skippedBefore + 1);
    }

    @Test
    void aDeletedBranchIsIgnored() {
        Map<String, Object> row = scenario.postAndProcess(WebhookFixtures.delivery("push-branch-deleted.json")
                .with("/ref", "refs/heads/main")
                .with("/installation/id", scenario.installationId)
                .with("/repository/id", scenario.repoId));

        assertIgnored(row, SkipRules.BRANCH_DELETED);
    }

    @Test
    void aSuspendedInstallationIsIgnored() {
        scenario.installationStatus("SUSPENDED");

        assertIgnored(scenario.postAndProcess(scenario.push(HEAD, NEXT)), SkipRules.INSTALLATION_INACTIVE);
    }

    @Test
    void anUnlinkedRepositoryIsIgnored() {
        Map<String, Object> row =
                scenario.postAndProcess(scenario.push(HEAD, NEXT).with("/repository/id", 17L));

        assertIgnored(row, SkipRules.NO_LINKED_APP);
    }

    @Test
    void anotherBranchIsIgnored() {
        Map<String, Object> row =
                scenario.postAndProcess(scenario.push(HEAD, NEXT).with("/ref", "refs/heads/feature/banner"));

        assertIgnored(row, SkipRules.NO_LINKED_APP);
    }

    @Test
    void anAppWithAutoDeployOffIsIgnored() {
        scenario.autoDeploy(appId, false);

        assertIgnored(scenario.postAndProcess(scenario.push(HEAD, NEXT)), SkipRules.NO_LINKED_APP);
    }

    @Test
    void aDeletedAppIsIgnored() {
        jdbc.update("UPDATE git_integration.apps SET status = 'DELETED' WHERE app_id = ?", appId);

        assertIgnored(scenario.postAndProcess(scenario.push(HEAD, NEXT)), SkipRules.NO_LINKED_APP);
    }

    @Test
    void aSkipMarkerInTheMessageBodyIsIgnored() {
        Map<String, Object> row = scenario.postAndProcess(
                scenario.push(HEAD, NEXT).with("/head_commit/message", "Update docs\n\nNothing to deploy [skip ci]"));

        assertIgnored(row, SkipRules.SKIP_MARKER);
    }

    @Test
    void aMonorepoPushBuildsOnlyTheAppWhoseDirectoryChanged() {
        UUID api = appId;
        UUID web = scenario.linkApp(orgId);
        scenario.rootDirectory(api, "apps/api");
        scenario.rootDirectory(web, "apps/web");

        Map<String, Object> row =
                scenario.postAndProcess(scenario.pushChanging(HEAD, NEXT, "apps/web/src/index.ts", "README.md"));

        assertThat(row).containsEntry("status", "PROCESSED");
        assertThat(scenario.published(orgId)).extracting(Published::appId).containsExactly(web.toString());
        assertThat(scenario.head(web)).contains(NEXT);
        assertThat(scenario.head(api)).as("a skipped app's head still advances").contains(NEXT);
        String after = sha('c');
        scenario.postAndProcess(scenario.pushChanging(NEXT, after, "apps/api/src/App.java"));
        assertThat(scenario.published(orgId))
                .extracting(Published::appId)
                .containsExactly(web.toString(), api.toString());
        assertThat(github.totalRequests())
                .as("the next push to the skipped app is a fast-forward")
                .isZero();
    }

    @Test
    void aMonorepoPushThatChangesNeitherAppIsIgnored() {
        UUID web = scenario.linkApp(orgId);
        scenario.rootDirectory(appId, "apps/api");
        scenario.rootDirectory(web, "apps/web");

        Map<String, Object> row = scenario.postAndProcess(scenario.pushChanging(HEAD, NEXT, "api-docs/guide.md"));

        assertThat(row)
                .containsEntry("status", "IGNORED")
                .containsEntry("outcome_reason", PushProcessor.PATH_UNCHANGED);
        assertThat(scenario.published(orgId)).isEmpty();
        assertThat(scenario.head(appId)).contains(NEXT);
        assertThat(scenario.head(web)).contains(NEXT);
    }

    @Test
    void aTwentyCommitPayloadMightBeTruncatedSoEveryAppBuilds() {
        UUID api = appId;
        UUID web = scenario.linkApp(orgId);
        scenario.rootDirectory(api, "apps/api");
        scenario.rootDirectory(web, "apps/web");

        Map<String, Object> row = scenario.postAndProcess(scenario.push("push-truncated-commits.json", HEAD, NEXT));

        assertThat(row).containsEntry("status", "PROCESSED");
        assertThat(scenario.published(orgId))
                .extracting(Published::appId)
                .containsExactlyInAnyOrder(api.toString(), web.toString());
    }

    @Test
    void aFailureAfterTheAppendRollsBackTheEventAndLeavesTheHead() {
        scenario.head(appId, HEAD);
        scenario.failWrites("branch_heads", "NEW.app_id = '" + appId + "'");

        Map<String, Object> row = scenario.postAndProcess(scenario.push(HEAD, NEXT));

        assertThat(row).containsEntry("status", "RECEIVED").containsEntry("attempts", 1);
        assertThat((String) row.get("last_error")).contains("injected failure");
        assertThat(scenario.published(orgId)).isEmpty();
        assertThat(scenario.head(appId)).contains(HEAD);
    }

    private void assertIgnored(Map<String, Object> row, String reason) {
        assertThat(row).containsEntry("status", "IGNORED").containsEntry("outcome_reason", reason);
        assertThat(scenario.published(orgId)).isEmpty();
        assertThat(scenario.head(appId)).isEmpty();
    }

    private double counter(String name, String tag, String value) {
        var counter = meters.find(name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }
}
