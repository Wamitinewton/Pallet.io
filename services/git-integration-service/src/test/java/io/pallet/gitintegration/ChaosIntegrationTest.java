package io.pallet.gitintegration;

import static io.pallet.gitintegration.push.PushScenario.sha;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.inbox.TransactionalInbox;
import io.pallet.common.resilience.ResilienceRegistries;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.delivery.DeliveryProcessor;
import io.pallet.gitintegration.push.PushScenario;
import io.pallet.gitintegration.push.PushScenario.Published;
import io.pallet.gitintegration.recovery.RecoveryJobs;
import io.pallet.gitintegration.repolink.RepoLinkApi;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.GitHubApiStub.LoggedDelivery;
import io.pallet.gitintegration.support.Outage;
import io.pallet.gitintegration.support.ReadModelFixtures;
import io.pallet.gitintegration.support.Tokens;
import io.pallet.gitintegration.support.TopicProbe;
import io.pallet.gitintegration.support.WebhookFixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The failure-mode table (ARCHITECTURE.md §Consistency and failure modes), one scenario per row, with the invariants
 * checked after each. Check runs under a failing GitHub are {@code CheckRunReporterIntegrationTest}'s.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, GitHubApiStub.Properties.class, Tokens.LocalDecoder.class})
@TestPropertySource(
        properties = {
            "spring.datasource.hikari.connection-timeout=1500",
            "spring.datasource.hikari.validation-timeout=500",
            "pallet.outbox.broker-backoff-max=PT2S",
            "pallet.resilience.policies.github-api.circuit-breaker.wait-duration-in-open-state=1s",
            "pallet.resilience.policies.github-api.circuit-breaker.permitted-calls-in-half-open-state=1"
        })
class ChaosIntegrationTest {

    private static final Duration DRAIN = Duration.ofSeconds(90);
    private static final int KAFKA_OUTAGE_PUSHES = 50;
    private static final int MAX_ROUNDS = 12;
    private static final String CONSUMER = "chaos-build-queue";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeliveryProcessor processor;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ResilienceRegistries resilience;

    @Autowired
    private TransactionalInbox inbox;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired
    private GenericContainer<?> redisContainer;

    private final List<PushScenario> scenarios = new ArrayList<>();
    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = resilience.circuitBreaker("github-api");
        breaker.reset();
        RecoveryJobs.resetCursors(context);
    }

    @AfterEach
    void tearDown() {
        breaker.reset();
        scenarios.forEach(PushScenario::cleanUp);
        jdbc.update("DELETE FROM git_integration.processed_events WHERE consumer = ?", CONSUMER);
    }

    @Test
    void kafkaFrozenDuringFiftyPushesLosesNothingAndDeliversEachOnceInOrder() throws Exception {
        PushScenario scenario = scenario();
        String orgId = org(scenario);
        UUID appId = scenario.linkApp(orgId);
        scenario.head(appId, commit(0));
        List<UUID> deliveries = new ArrayList<>();

        Outage.during(kafka, () -> {
            for (int i = 1; i <= KAFKA_OUTAGE_PUSHES; i++) {
                deliveries.add(scenario.post(scenario.push(commit(i - 1), commit(i))));
            }
            processAll(scenario, deliveries);
            assertThat(deliveries)
                    .allSatisfy(id -> assertThat(scenario.delivery(id)).containsEntry("status", "PROCESSED"));
            assertThat(unpublished(orgId)).isEqualTo(KAFKA_OUTAGE_PUSHES);
        });

        List<JsonNode> arrived;
        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), GitPushReceived.TYPE)) {
            arrived = probe.awaitKey(orgId, KAFKA_OUTAGE_PUSHES).stream()
                    .map(record -> JSON.readTree(record.value()))
                    .toList();
        }
        Set<String> firstSeen = new LinkedHashSet<>();
        arrived.forEach(event -> firstSeen.add(event.path("eventId").asString()));
        assertThat(firstSeen).hasSize(KAFKA_OUTAGE_PUSHES);
        assertThat(consumedOnce(arrived))
                .extracting(event -> event.path("commitSha").asString())
                .containsExactlyElementsOf(commits(1, KAFKA_OUTAGE_PUSHES));
        invariantsHold(scenario, Map.of(appId, orgId));
    }

    @Test
    void postgresFrozenAnswers503AndTheSweeperRecoversThePushAfterwards() throws Exception {
        PushScenario scenario = scenario();
        String orgId = org(scenario);
        UUID appId = scenario.linkApp(orgId);
        scenario.head(appId, commit(0));
        WebhookFixtures.Delivery push = scenario.push(commit(0), commit(1));

        int during = Outage.during(postgres, () -> {
            Thread.sleep(Outage.IDLE_PAST_ALIVE_BYPASS_MS);
            return push.post(mvc).getStatus();
        });

        assertThat(during).isEqualTo(503);
        github.stubHookDeliveries(List.of(
                LoggedDelivery.failed(7001, push.deliveryId(), Instant.now().truncatedTo(ChronoUnit.SECONDS), "push")));
        github.stubRedeliveriesAccepted();
        assertThat(RecoveryJobs.sweep(context)).isEqualTo(1);
        assertThat(github.redeliveryRequests()).containsExactly(7001L);
        UUID id = scenario.post(push);
        assertThat(scenario.process(id)).containsEntry("status", "PROCESSED");
        assertThat(scenario.published(orgId)).extracting(Published::commitSha).containsExactly(commit(1));
        invariantsHold(scenario, Map.of(appId, orgId));
    }

    @Test
    void aFailingGitHubStopsOnlyAnomalousPushesOpensTheBreakerParksNothingAndRecovers() throws Exception {
        PushScenario scenario = scenario();
        String orgId = org(scenario);
        UUID appId = scenario.linkApp(orgId);
        scenario.head(appId, commit(0));
        github.stub5xx(GitHubApiStub.tokenPath(scenario.installationId));

        UUID anomalous = scenario.post(scenario.push(sha('9'), sha('8')));
        for (int round = 0; round < MAX_ROUNDS && breaker.getState() == CircuitBreaker.State.CLOSED; round++) {
            scenario.makeDue(anomalous);
            scenario.process(anomalous);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        UUID ordinary = scenario.post(scenario.push(commit(0), commit(1)));
        assertThat(scenario.process(ordinary))
                .as("the fast path needs no GitHub call")
                .containsEntry("status", "PROCESSED");
        for (int round = 0; round < MAX_ROUNDS; round++) {
            scenario.makeDue(anomalous);
            scenario.process(anomalous);
        }
        assertThat(scenario.delivery(anomalous)).containsEntry("status", "RECEIVED");

        github.server().resetAll();
        github.stubTokenMint(scenario.installationId);
        github.stubCompare(scenario.repoId, commit(1), sha('8'), "ahead");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            scenario.makeDue(anomalous);
            assertThat(scenario.process(anomalous)).containsEntry("status", "PROCESSED");
        });

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(scenario.published(orgId)).extracting(Published::commitSha).containsExactly(commit(1), sha('8'));
        invariantsHold(scenario, Map.of(appId, orgId));
    }

    @Test
    void oneRateLimitedInstallationSkipsItsBackgroundWorkWithoutHoldingUpAnother() throws Exception {
        PushScenario limited = scenario();
        PushScenario healthy = scenario();
        String limitedOrg = org(limited);
        String healthyOrg = org(healthy);
        UUID limitedApp = limited.linkApp(limitedOrg);
        UUID healthyApp = healthy.linkApp(healthyOrg);
        limited.head(limitedApp, commit(0));
        healthy.head(healthyApp, commit(0));
        github.stubTokenMint(limited.installationId);
        github.stubTokenMint(healthy.installationId);
        String limitedBranch = GitHubApiStub.branchPath(limited.repoId, "main");
        github.stubRateLimited(limitedBranch, Instant.now().plus(Duration.ofHours(1)));
        github.stubBranch(healthy.repoId, "main", commit(1));
        github.stubCompare(healthy.repoId, commit(0), commit(1), "ahead");

        RecoveryJobs.reconcile(context);
        int limitedCalls = github.requests(limitedBranch).size();
        github.stubBranch(healthy.repoId, "main", commit(2));
        github.stubCompare(healthy.repoId, commit(1), commit(2), "ahead");
        RecoveryJobs.reconcile(context);

        assertThat(limitedCalls).isEqualTo(1);
        assertThat(github.requests(limitedBranch))
                .as("background work skipped once the budget is gone")
                .hasSize(1);
        assertThat(healthy.published(healthyOrg))
                .extracting(Published::commitSha)
                .containsExactly(commit(1), commit(2));
        assertThat(limited.published(limitedOrg)).isEmpty();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        invariantsHold(limited, Map.of(limitedApp, limitedOrg));
        invariantsHold(healthy, Map.of(healthyApp, healthyOrg));
    }

    @Test
    void aProcessorDyingInsideTheDeliveryTransactionLeavesNothingAndTheRetryPublishesOnce() throws Exception {
        PushScenario scenario = scenario();
        String orgId = org(scenario);
        UUID appId = scenario.linkApp(orgId);
        scenario.head(appId, commit(0));
        scenario.failWrites("outbox_events", "NEW.org_id = '" + orgId + "'");

        UUID id = scenario.post(scenario.push(commit(0), commit(1)));
        Map<String, Object> died = scenario.process(id);

        assertThat(died).containsEntry("status", "RECEIVED").containsEntry("attempts", 1);
        assertThat(scenario.head(appId))
                .as("the head advance rolled back with the event")
                .contains(commit(0));
        assertThat(scenario.published(orgId)).isEmpty();

        scenario.clearFaults();
        scenario.makeDue(id);
        assertThat(scenario.process(id)).containsEntry("status", "PROCESSED");
        assertThat(scenario.published(orgId)).extracting(Published::commitSha).containsExactly(commit(1));
        invariantsHold(scenario, Map.of(appId, orgId));
    }

    @Test
    void aRelayDyingBetweenPublishAndMarkRepublishesTheSameEventIdAndTheInboxCountsItOnce() throws Exception {
        PushScenario scenario = scenario();
        String orgId = org(scenario);
        UUID appId = scenario.linkApp(orgId);
        scenario.head(appId, commit(0));
        scenario.failWrites("outbox_events", "NEW.org_id = '" + orgId + "' AND NEW.status = 'PUBLISHED'");

        List<JsonNode> arrived;
        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), GitPushReceived.TYPE)) {
            scenario.process(scenario.post(scenario.push(commit(0), commit(1))));
            arrived = probe.awaitKey(orgId, 2).stream()
                    .map(record -> JSON.readTree(record.value()))
                    .toList();
        } finally {
            scenario.clearFaults();
        }

        assertThat(arrived).hasSizeGreaterThanOrEqualTo(2);
        assertThat(arrived)
                .extracting(event -> event.path("eventId").asString())
                .containsOnly(arrived.getFirst().path("eventId").asString());
        assertThat(consumedOnce(arrived)).hasSize(1);
        invariantsHold(scenario, Map.of(appId, orgId));
    }

    @Test
    void redisFrozenFailsOnlyTheSessionEndpoints() throws Exception {
        PushScenario scenario = scenario();
        String orgId = org(scenario);
        UUID appId = scenario.linkApp(orgId);
        scenario.head(appId, commit(0));
        String member = new ReadModelFixtures(jdbc).newMember(orgId, "developer", "ACTIVE");
        RepoLinkApi api = new RepoLinkApi(mvc, JSON, github).as(member);

        Outage.during(redisContainer, () -> {
            MockHttpServletResponse session = api.perform(get("/api/v1/git-integration/github/session"));
            assertThat(session.getStatus()).isEqualTo(502);
            assertThat(session.getContentAsString()).contains("EXTERNAL_SERVICE_ERROR");
            assertThat(api.get(orgId, appId).getStatus())
                    .as("reads keep working")
                    .isEqualTo(200);
            UUID id = scenario.post(scenario.push(commit(0), commit(1)));
            assertThat(scenario.process(id)).containsEntry("status", "PROCESSED");
        });

        assertThat(scenario.published(orgId)).extracting(Published::commitSha).containsExactly(commit(1));
        invariantsHold(scenario, Map.of(appId, orgId));
    }

    /**
     * No active repo link hangs off an unlinked installation link; each app's head is the last commit it published, so
     * it never moved behind one; nothing waits past the worst-case backoff; the outbox drains.
     */
    private void invariantsHold(PushScenario scenario, Map<UUID, String> apps) {
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM git_integration.repo_links r
                          JOIN git_integration.installation_links l
                            ON l.installation_id = r.installation_id AND l.org_id = r.org_id
                         WHERE r.status = 'ACTIVE' AND l.status = 'UNLINKED'
                        """, Integer.class)).isZero();
        apps.forEach((appId, orgId) -> {
            List<String> published = scenario.published(orgId).stream()
                    .filter(event -> event.payload().path("appId").asString().equals(appId.toString()))
                    .map(Published::commitSha)
                    .toList();
            if (!published.isEmpty()) {
                assertThat(scenario.head(appId)).contains(published.getLast());
            }
        });
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM git_integration.webhook_deliveries
                         WHERE status = 'RECEIVED' AND received_at < now() - interval '10 minutes'
                        """, Integer.class)).isZero();
        await().atMost(DRAIN)
                .untilAsserted(() -> apps.values()
                        .forEach(orgId -> assertThat(unpublished(orgId))
                                .as("outbox drained for " + orgId)
                                .isZero()));
    }

    /** Each event as a consumer with an inbox sees it: the first delivery of each id, in arrival order. */
    private List<JsonNode> consumedOnce(List<JsonNode> arrived) {
        List<JsonNode> consumed = new ArrayList<>();
        for (JsonNode event : arrived) {
            UUID eventId = UUID.fromString(event.path("eventId").asString());
            Boolean first = transaction.execute(status -> inbox.firstDelivery(CONSUMER, eventId));
            if (Boolean.TRUE.equals(first)) {
                consumed.add(event);
            }
        }
        return consumed;
    }

    private void processAll(PushScenario scenario, List<UUID> ids) {
        for (int cycle = 0; cycle < KAFKA_OUTAGE_PUSHES && scenario.pending(ids); cycle++) {
            processor.processBatch();
        }
    }

    private int unpublished(String orgId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM git_integration.outbox_events WHERE org_id = ? AND status <> 'PUBLISHED'",
                Integer.class,
                orgId);
    }

    private PushScenario scenario() {
        PushScenario scenario = new PushScenario(mvc, jdbc, processor);
        scenarios.add(scenario);
        return scenario;
    }

    private static String org(PushScenario scenario) {
        return scenario.newOrg();
    }

    private static String commit(int index) {
        return "%040x".formatted(index + 1);
    }

    private static List<String> commits(int from, int to) {
        List<String> commits = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            commits.add(commit(i));
        }
        return commits;
    }
}
