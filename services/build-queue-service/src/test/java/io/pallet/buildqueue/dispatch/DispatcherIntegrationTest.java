package io.pallet.buildqueue.dispatch;

import static io.pallet.buildqueue.dispatch.DispatchDriver.buildIdOf;
import static io.pallet.buildqueue.dispatch.DispatchDriver.text;
import static io.pallet.buildqueue.support.PushEvents.push;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.pallet.buildqueue.build.Build;
import io.pallet.buildqueue.build.BuildIds;
import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.config.BuildQueueProperties;
import io.pallet.buildqueue.dispatch.Dispatcher.Round;
import io.pallet.buildqueue.dispatch.Dispatcher.Round.Outcome;
import io.pallet.buildqueue.observability.QueueMetrics;
import io.pallet.buildqueue.observability.QueueMetrics.RoundResult;
import io.pallet.buildqueue.support.TopicProbe;
import io.pallet.buildqueue.support.TopicProbe.Received;
import io.pallet.common.events.BuildDispatched;
import io.pallet.common.events.EventHeaders;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class DispatcherIntegrationTest {

    /** Mapped to the {@code team} profile (max-concurrent 3) in application-test.yml. */
    private static final String TEAM_ORG = "org-dispatch-team";

    /** Both cover exactly the waiting builds; which one is cheaper depends on the planner's statistics. */
    private static final List<String> QUEUED_PARTIAL_INDEXES = List.of("ix_builds_queued", "ix_builds_queue_age");

    private static final String COMMIT_FAILS_PREFIX = "org-dispatch-commit-fails-";

    private static final List<String> DISPATCHED_FIELDS = List.of(
            "eventId",
            "eventType",
            "orgId",
            "occurredAt",
            "buildId",
            "attempt",
            "appId",
            "branch",
            "commitSha",
            "pushEventId",
            "trigger",
            "provider",
            "installationId",
            "repoId",
            "repo",
            "rootDirectory",
            "startBy",
            "maxDurationSeconds");

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private BuildRepository builds;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private Dispatcher dispatcher;

    @Autowired
    private DispatchQuery dispatchQuery;

    @Autowired
    private BuildQueueProperties properties;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private KafkaContainer kafka;

    private DispatchDriver driver;

    @BeforeEach
    void setUp() {
        driver = new DispatchDriver(publisher, builds, jdbc, jsonMapper);
        driver.quiesce();
    }

    @AfterEach
    void tearDown() {
        driver.quiesce();
        jdbc.execute("DROP TRIGGER IF EXISTS fail_dispatch_at_commit ON build_queue.outbox_events");
        jdbc.execute("DROP FUNCTION IF EXISTS build_queue.fail_dispatch_at_commit()");
    }

    @Test
    void aQueuedBuildIsDispatchedAndItsEventCarriesTheStoredDeadline() {
        String orgId = newOrg();
        GitPushReceived event = push(orgId, newApp()).build();
        UUID buildId = buildIdOf(event);

        try (TopicProbe dispatched = new TopicProbe(kafka.getBootstrapServers(), Topics.BUILD_DISPATCHED)) {
            driver.queue(event);

            Round round = dispatcher.runRound();

            assertThat(round).isEqualTo(new Round(Outcome.DISPATCHED, 1, false));
            Build build = driver.build(event);
            assertThat(build.status()).isEqualTo(BuildStatus.DISPATCHED);
            assertThat(build.attempt()).isOne();
            assertThat(build.version()).isOne();
            assertThat(build.dispatchedAt()).isNotNull();
            assertThat(build.startDeadline())
                    .isEqualTo(build.dispatchedAt().plus(properties.dispatch().startTimeout()));
            assertThat(driver.lastServedAt(orgId)).contains(build.dispatchedAt());

            List<Received> records = dispatched.awaitKey(orgId, 1);
            assertThat(records).hasSize(1);
            assertThat(records.getFirst().header(EventHeaders.EVENT_ID))
                    .isEqualTo(BuildIds.dispatchedEventId(buildId, 1).toString());
            JsonNode published = driver.json(records.getFirst().value());
            assertThat(published.propertyNames()).containsExactlyInAnyOrderElementsOf(DISPATCHED_FIELDS);
            assertThat(text(published, "eventType")).isEqualTo(BuildDispatched.TYPE);
            assertThat(text(published, "orgId")).isEqualTo(orgId);
            assertThat(text(published, "buildId")).isEqualTo(buildId.toString());
            assertThat(published.path("attempt").asInt()).isOne();
            assertThat(text(published, "appId")).isEqualTo(event.appId());
            assertThat(text(published, "branch")).isEqualTo(event.branch());
            assertThat(text(published, "commitSha")).isEqualTo(event.commitSha());
            assertThat(text(published, "pushEventId")).isEqualTo(event.eventId().toString());
            assertThat(text(published, "trigger")).isEqualTo(event.trigger());
            assertThat(text(published, "provider")).isEqualTo(event.provider());
            assertThat(published.path("installationId").asLong()).isEqualTo(event.installationId());
            assertThat(published.path("repoId").asLong()).isEqualTo(event.repoId());
            assertThat(text(published, "repo")).isEqualTo(event.repo());
            assertThat(text(published, "rootDirectory")).isEqualTo(event.rootDirectory());
            assertThat(Instant.parse(text(published, "startBy"))).isEqualTo(build.startDeadline());
            assertThat(published.path("maxDurationSeconds").asLong())
                    .isEqualTo(properties.build().maxDuration().toSeconds());
        }
    }

    @Test
    void anOrgAtItsConcurrencyLimitWaitsWhileAnotherOrgIsServed() {
        String busyOrg = newOrg();
        String otherOrg = newOrg();
        GitPushReceived first = push(busyOrg, newApp()).build();
        driver.queue(first);
        dispatcher.runRound();
        GitPushReceived second = push(busyOrg, newApp()).build();
        GitPushReceived other = push(otherOrg, newApp()).build();
        driver.queue(second, other);

        Round round = dispatcher.runRound();

        assertThat(round.dispatched()).isOne();
        assertThat(driver.status(first)).isEqualTo(BuildStatus.DISPATCHED);
        assertThat(driver.status(second)).isEqualTo(BuildStatus.QUEUED);
        assertThat(driver.status(other)).isEqualTo(BuildStatus.DISPATCHED);
    }

    @Test
    void anAppWithABuildInFlightWaitsEvenWhenItsOrgHasRoom() {
        String appId = newApp();
        GitPushReceived main = push(TEAM_ORG, appId).branch("main").build();
        driver.queue(main);
        dispatcher.runRound();
        GitPushReceived feature = push(TEAM_ORG, appId).branch("feature").build();
        GitPushReceived neighbour = push(TEAM_ORG, newApp()).build();
        driver.queue(feature, neighbour);

        Round round = dispatcher.runRound();

        assertThat(round.dispatched()).isOne();
        assertThat(driver.status(main)).isEqualTo(BuildStatus.DISPATCHED);
        assertThat(driver.status(feature)).isEqualTo(BuildStatus.QUEUED);
        assertThat(driver.status(neighbour)).isEqualTo(BuildStatus.DISPATCHED);
        assertThat(dispatcher.runRound().outcome()).isEqualTo(Outcome.IDLE);
    }

    @Test
    void withinAnOrgAnInteractiveBuildGoesBeforeAnEarlierAutomaticOne() {
        String orgId = newOrg();
        GitPushReceived automatic =
                push(orgId, newApp()).trigger(GitPushReceived.TRIGGER_WEBHOOK).build();
        GitPushReceived interactive =
                push(orgId, newApp()).trigger(GitPushReceived.TRIGGER_MANUAL).build();
        driver.queue(automatic, interactive);
        assertThat(driver.build(automatic).enqueuedAt())
                .isBefore(driver.build(interactive).enqueuedAt());

        dispatcher.runRound();

        assertThat(driver.status(interactive)).isEqualTo(BuildStatus.DISPATCHED);
        assertThat(driver.status(automatic)).isEqualTo(BuildStatus.QUEUED);
    }

    @Test
    void aFailureAfterTheOutboxAppendRollsTheWholeRoundBack() {
        failDispatchCommitsForOrgsStartingWith(COMMIT_FAILS_PREFIX);
        String orgId = COMMIT_FAILS_PREFIX + UUID.randomUUID();
        GitPushReceived event = push(orgId, newApp()).build();
        driver.queue(event);
        long failuresBefore = roundCount(RoundResult.ERROR);

        assertThatThrownBy(dispatcher::runRound).rootCause().hasMessageContaining("injected by test");

        Build build = driver.build(event);
        assertThat(build.status()).isEqualTo(BuildStatus.QUEUED);
        assertThat(build.attempt()).isZero();
        assertThat(build.version()).isZero();
        assertThat(build.dispatchedAt()).isNull();
        assertThat(build.startDeadline()).isNull();
        assertThat(driver.dispatchedEvents(orgId)).isEmpty();
        assertThat(driver.lastServedAt(orgId)).isEmpty();
        assertThat(roundCount(RoundResult.ERROR)).isEqualTo(failuresBefore + 1);
    }

    @Test
    void aDeletedOrgsWaitingBuildsAreNotCandidates() {
        String orgId = newOrg();
        GitPushReceived event = push(orgId, newApp()).build();
        driver.queue(event);
        driver.deleteOrg(orgId);

        assertThat(dispatcher.runRound().outcome()).isEqualTo(Outcome.IDLE);
        assertThat(driver.status(event)).isEqualTo(BuildStatus.QUEUED);
        assertThat(driver.dispatchedEvents(orgId)).isEmpty();
    }

    @Test
    void theCandidateQueryReadsThePartialIndexesNotEveryBuild() {
        try {
            seedPlanningVolume();

            List<String> plan = explainCandidates();

            assertThat(plan).noneMatch(line -> line.contains("Seq Scan on builds"));
            assertThat(plan).anyMatch(line -> QUEUED_PARTIAL_INDEXES.stream().anyMatch(line::contains));
            assertThat(plan).anyMatch(line -> line.contains("ix_builds_in_flight"));
        } finally {
            driver.quiesce();
            jdbc.update("DELETE FROM build_queue.apps WHERE app_id LIKE 'app-plan-%'");
            jdbc.update("DELETE FROM build_queue.orgs WHERE org_id LIKE 'org-plan-%'");
        }
    }

    @Nested
    @TestPropertySource(properties = "pallet.build-queue.dispatch.global-concurrency=2")
    class WithCapacityForTwo {

        @Autowired
        private Dispatcher smallFleet;

        @Test
        void fiveOrgsWaitingGetExactlyTwoSlotsInOrgOrder() {
            List<String> orgIds = IntStream.range(0, 5)
                    .mapToObj(i -> "org-capacity-" + UUID.randomUUID())
                    .sorted()
                    .toList();
            List<GitPushReceived> pushes =
                    orgIds.stream().map(orgId -> push(orgId, newApp()).build()).toList();
            driver.queue(pushes.toArray(GitPushReceived[]::new));

            Round round = smallFleet.runRound();

            assertThat(round).isEqualTo(new Round(Outcome.DISPATCHED, 2, false));
            assertThat(pushes)
                    .extracting(driver::status)
                    .containsExactly(
                            BuildStatus.DISPATCHED,
                            BuildStatus.DISPATCHED,
                            BuildStatus.QUEUED,
                            BuildStatus.QUEUED,
                            BuildStatus.QUEUED);
            assertThat(smallFleet.runRound().outcome()).isEqualTo(Outcome.IDLE);
        }
    }

    @Nested
    @TestPropertySource(properties = "pallet.build-queue.dispatch.paused=true")
    class WhilePaused {

        @Autowired
        private Dispatcher pausedDispatcher;

        @Test
        void nothingIsDispatchedAndWaitingRowsAreUntouched() {
            String orgId = newOrg();
            GitPushReceived event = push(orgId, newApp()).build();
            driver.queue(event);

            assertThat(pausedDispatcher.runRound()).isEqualTo(new Round(Outcome.PAUSED, 0, false));

            Build build = driver.build(event);
            assertThat(build.status()).isEqualTo(BuildStatus.QUEUED);
            assertThat(build.version()).isZero();
            assertThat(build.attempt()).isZero();
            assertThat(driver.dispatchedEvents(orgId)).isEmpty();
            assertThat(driver.lastServedAt(orgId)).isEmpty();
        }
    }

    /** Thousands of finished builds around a small live queue, as retention leaves it, then fresh statistics. */
    private void seedPlanningVolume() {
        jdbc.update("""
                INSERT INTO build_queue.orgs (org_id, status)
                SELECT 'org-plan-' || g, 'ACTIVE' FROM generate_series(1, 200) g
                """);
        jdbc.update("""
                INSERT INTO build_queue.apps (app_id, org_id, status)
                SELECT 'app-plan-' || g, 'org-plan-' || (g % 200 + 1), 'ACTIVE' FROM generate_series(1, 1000) g
                """);
        jdbc.update("""
                INSERT INTO build_queue.builds
                    (build_id, org_id, app_id, branch, commit_sha, push_event_id, trigger, priority_class, provider,
                     installation_id, repo_id, repo, status, status_reason, finished_at)
                SELECT gen_random_uuid(), 'org-plan-' || ((g % 1000 + 1) % 200 + 1), 'app-plan-' || (g % 1000 + 1),
                       'branch-' || g, repeat('a', 40), gen_random_uuid(), 'WEBHOOK', 1, 'GITHUB', 1, 1, 'octo/plan',
                       'SUCCEEDED', NULL, now()
                  FROM generate_series(1, 5000) g
                """);
        jdbc.update("""
                INSERT INTO build_queue.builds
                    (build_id, org_id, app_id, branch, commit_sha, push_event_id, trigger, priority_class, provider,
                     installation_id, repo_id, repo, status)
                SELECT gen_random_uuid(), 'org-plan-' || (g % 200 + 1), 'app-plan-' || g, 'queued-' || g,
                       repeat('b', 40), gen_random_uuid(), 'WEBHOOK', 1, 'GITHUB', 1, 1, 'octo/plan', 'QUEUED'
                  FROM generate_series(1, 300) g
                """);
        jdbc.update("""
                INSERT INTO build_queue.builds
                    (build_id, org_id, app_id, branch, commit_sha, push_event_id, trigger, priority_class, provider,
                     installation_id, repo_id, repo, status, attempt, dispatched_at, start_deadline)
                SELECT gen_random_uuid(), 'org-plan-' || (g % 200 + 1), 'app-plan-' || g, 'running-' || g,
                       repeat('c', 40), gen_random_uuid(), 'WEBHOOK', 1, 'GITHUB', 1, 1, 'octo/plan', 'DISPATCHED',
                       1, now(), now() + interval '5 minutes'
                  FROM generate_series(501, 550) g
                """);
        jdbc.execute("VACUUM ANALYZE build_queue.builds, build_queue.orgs, build_queue.apps");
    }

    private List<String> explainCandidates() {
        DispatchQuery.Statement statement =
                dispatchQuery.candidates(properties.dispatch().batchSize());
        return new NamedParameterJdbcTemplate(jdbc)
                .queryForList("EXPLAIN " + statement.sql(), statement.params(), String.class);
    }

    /** A deferred constraint trigger raises at commit, after the round has updated, appended and marked the org. */
    private void failDispatchCommitsForOrgsStartingWith(String prefix) {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION build_queue.fail_dispatch_at_commit() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'dispatch commit failure injected by test';
                END $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER fail_dispatch_at_commit AFTER INSERT ON build_queue.outbox_events
                    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
                    WHEN (NEW.event_type = '%s' AND NEW.org_id LIKE '%s%%')
                    EXECUTE FUNCTION build_queue.fail_dispatch_at_commit()
                """.formatted(BuildDispatched.TYPE, prefix));
    }

    private long roundCount(RoundResult result) {
        Timer timer = meterRegistry
                .find(QueueMetrics.DISPATCH_ROUND)
                .tag(QueueMetrics.TAG_RESULT, QueueMetrics.tag(result))
                .timer();
        return timer == null ? 0 : timer.count();
    }

    private static String newOrg() {
        return "org-" + UUID.randomUUID();
    }

    private static String newApp() {
        return "app-" + UUID.randomUUID();
    }
}
