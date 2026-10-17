package io.pallet.buildqueue.lease;

import static io.pallet.buildqueue.support.PushEvents.push;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.pallet.buildqueue.build.Build;
import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.config.BuildQueueProperties;
import io.pallet.buildqueue.dispatch.DispatchWakeups;
import io.pallet.buildqueue.dispatch.Dispatcher;
import io.pallet.buildqueue.support.StubBuilder;
import io.pallet.buildqueue.support.StubBuilder.Mode;
import io.pallet.common.events.BuildCancelled;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.PlatformEventPublisher;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A stub build-service that honours the contract, against the real dispatch and sweep loops, both started for each
 * test only so this cached context never sweeps another class's builds.
 */
@LeaseTestContext
class StartDeadlineIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private BuildRepository builds;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private BuildQueueProperties properties;

    @Autowired
    private StubBuilder stubBuilder;

    @Autowired
    private Dispatcher dispatcher;

    @Autowired
    private LeaseSweeper sweeper;

    @Autowired
    private LeaseSweepSchedule sweepSchedule;

    @Autowired
    private ApplicationContext context;

    private LeaseDriver driver;

    @BeforeEach
    void setUp() {
        driver = new LeaseDriver(publisher, builds, jdbc, jsonMapper, kafka.getBootstrapServers());
        driver.quiesce();
    }

    @AfterEach
    void tearDown() {
        sweepSchedule.stop();
        DispatchWakeups.of(context).stop();
        stubBuilder.stop();
        driver.quiesce();
    }

    @Test
    void aBuildNeverStartedIsRedispatchedAsItselfUntilItsAttemptsRunOutThenFreesTheApp() {
        stubBuilder.start(Mode.ignore());
        startLoops();
        String org = newOrg();
        String app = newApp();
        GitPushReceived first = push(org, app).build();
        GitPushReceived next = push(org, app).build();

        driver.publish(first);
        driver.awaitStatus(first, BuildStatus.DISPATCHED);
        driver.publish(next);
        driver.awaitStatus(next, BuildStatus.QUEUED);

        Build expired = driver.awaitStatus(first, BuildStatus.EXPIRED);
        assertThat(expired.statusReason()).isEqualTo(BuildCancelled.REASON_START_TIMEOUT);
        assertThat(expired.attempt()).isEqualTo(properties.dispatch().maxAttempts());

        List<JsonNode> dispatches = driver.published(
                Topics.BUILD_DISPATCHED, first, properties.dispatch().maxAttempts());
        assertThat(dispatches)
                .extracting(event -> event.path("attempt").asInt())
                .containsExactly(1, 2, 3);
        assertThat(dispatches)
                .extracting(event -> Instant.parse(event.path("startBy").asString()))
                .isSorted()
                .doesNotHaveDuplicates();
        assertThat(dispatches)
                .extracting(event -> event.path("eventId").asString())
                .doesNotHaveDuplicates();
        assertThat(driver.published(Topics.BUILD_CANCELLED, first, 1))
                .singleElement()
                .satisfies(event ->
                        assertThat(event.path("reason").asString()).isEqualTo(BuildCancelled.REASON_START_TIMEOUT));

        driver.awaitStatus(next, BuildStatus.DISPATCHED);
    }

    @Test
    void aRedispatchStartsTheBuildTheFirstAttemptNeverDidAndItRunsOnce() {
        stubBuilder.start(Mode.ignore());
        startLoops();
        GitPushReceived push = push(newOrg(), newApp()).build();
        String buildId = LeaseDriver.buildIdOf(push).toString();

        driver.publish(push);
        await().atMost(WAIT).until(() -> stubBuilder.received(buildId) == 1);
        stubBuilder.mode(Mode.normal());

        Build succeeded = driver.awaitStatus(push, BuildStatus.SUCCEEDED);
        assertThat(succeeded.attempt()).isEqualTo(2);
        assertThat(succeeded.statusReason()).isNull();
        assertThat(stubBuilder.starts(buildId)).isOne();
    }

    @Test
    void anAttemptPickedUpAfterItsStartByIsRefusedAndTheRedispatchRunsOnce() {
        stubBuilder.start(Mode.slowPickup(properties.dispatch().startTimeout().plusSeconds(1)));
        startLoops();
        GitPushReceived push = push(newOrg(), newApp()).build();
        String buildId = LeaseDriver.buildIdOf(push).toString();

        driver.publish(push);
        await().atMost(WAIT).until(() -> stubBuilder.received(buildId) == 1);
        stubBuilder.mode(Mode.normal());

        Build succeeded = driver.awaitStatus(push, BuildStatus.SUCCEEDED);
        await().atMost(WAIT).until(() -> stubBuilder.droppedLate() == 1);
        assertThat(succeeded.attempt()).isEqualTo(2);
        assertThat(stubBuilder.starts(buildId)).isOne();
    }

    @Test
    void aPausedDispatcherNeitherRedispatchesNorExpiresAndItsResumeSendsTheNextAttempt() {
        GitPushReceived push = push(newOrg(), newApp()).build();
        driver.publish(push);
        driver.awaitStatus(push, BuildStatus.QUEUED);
        dispatcher.runRound();
        Build dispatched = driver.build(push);
        assertThat(dispatched.status()).isEqualTo(BuildStatus.DISPATCHED);
        LeaseSweeper pausedSweeper = LeaseSweepers.withDispatchPaused(context);

        driver.awaitDatabaseTimePast(dispatched.startDeadline());
        for (int sweep = 0; sweep < properties.dispatch().maxAttempts(); sweep++) {
            pausedSweeper.sweep();
        }
        Build held = driver.build(push);
        assertThat(held.status()).isEqualTo(BuildStatus.DISPATCHED);
        assertThat(held.attempt()).isOne();
        assertThat(held.startDeadline()).isEqualTo(dispatched.startDeadline());

        assertThat(sweeper.sweep().redispatchAttempts()).contains(2);

        Build redispatched = driver.build(push);
        assertThat(redispatched.status()).isEqualTo(BuildStatus.DISPATCHED);
        assertThat(redispatched.attempt()).isEqualTo(2);
        assertThat(redispatched.startDeadline()).isAfter(dispatched.startDeadline());
        assertThat(driver.published(Topics.BUILD_DISPATCHED, push, 2))
                .extracting(event -> event.path("attempt").asInt())
                .containsExactly(1, 2);
    }

    private void startLoops() {
        DispatchWakeups.of(context).start();
        sweepSchedule.start();
    }

    private static String newOrg() {
        return "org-" + UUID.randomUUID();
    }

    private static String newApp() {
        return "app-" + UUID.randomUUID();
    }
}
