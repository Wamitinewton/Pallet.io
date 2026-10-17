package io.pallet.buildqueue.intake;

import static io.pallet.buildqueue.intake.IntakeDriver.buildIdOf;
import static io.pallet.buildqueue.support.PushEvents.push;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.buildqueue.build.Build;
import io.pallet.buildqueue.build.BuildIds;
import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.build.PriorityClass;
import io.pallet.buildqueue.dispatch.DispatchSignal;
import io.pallet.buildqueue.observability.QueueMetrics;
import io.pallet.buildqueue.projection.AppProjection;
import io.pallet.buildqueue.projection.AppProjectionRepository;
import io.pallet.buildqueue.projection.OrgProjectionRepository;
import io.pallet.buildqueue.support.PgNotifications;
import io.pallet.buildqueue.support.ReadModelFixtures;
import io.pallet.buildqueue.support.TopicProbe;
import io.pallet.buildqueue.support.TopicProbe.Received;
import io.pallet.common.events.BuildQueued;
import io.pallet.common.events.EventHeaders;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.MessagingProperties;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class IntakeIntegrationTest {

    private static final Duration NOTIFY_WAIT = Duration.ofSeconds(10);
    private static final Duration SILENCE = Duration.ofSeconds(2);
    private static final String COMMIT_FAILS_PREFIX = "org-commit-fails-";

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private BuildRepository builds;

    @Autowired
    private AppProjectionRepository apps;

    @Autowired
    private OrgProjectionRepository orgs;

    @Autowired
    private BuildIntake intake;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private MessagingProperties messaging;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private PostgreSQLContainer postgres;

    private IntakeDriver driver;
    private ReadModelFixtures fixtures;

    @BeforeEach
    void setUp() {
        driver = new IntakeDriver(publisher, builds, jdbc, jsonMapper, listeners, kafka.getBootstrapServers());
        fixtures = new ReadModelFixtures(jdbc);
    }

    @AfterEach
    void tearDown() {
        driver.close();
        fixtures.cleanUp();
        jdbc.execute("DROP TRIGGER IF EXISTS fail_at_commit ON build_queue.builds");
        jdbc.execute("DROP FUNCTION IF EXISTS build_queue.fail_at_commit()");
    }

    @Test
    void aValidPushQueuesOneBuildPublishesBuildQueuedAndWakesTheDispatcherAfterCommit() {
        String orgId = newOrg();
        GitPushReceived event =
                push(orgId, newApp()).trigger(GitPushReceived.TRIGGER_MANUAL).build();
        UUID buildId = buildIdOf(event);

        try (PgNotifications wakeups = new PgNotifications(postgres, DispatchSignal.CHANNEL);
                TopicProbe queued = new TopicProbe(kafka.getBootstrapServers(), Topics.BUILD_QUEUED)) {
            driver.publish(event);

            Build build = driver.awaitBuild(event, BuildStatus.QUEUED);
            assertThat(build.buildId()).isEqualTo(buildId);
            assertThat(build.orgId()).isEqualTo(orgId);
            assertThat(build.appId()).isEqualTo(event.appId());
            assertThat(build.branch()).isEqualTo(event.branch());
            assertThat(build.commitSha()).isEqualTo(event.commitSha());
            assertThat(build.pushEventId()).isEqualTo(event.eventId());
            assertThat(build.trigger()).isEqualTo(GitPushReceived.TRIGGER_MANUAL);
            assertThat(build.priorityClass()).isEqualTo(PriorityClass.INTERACTIVE);
            assertThat(build.provider()).isEqualTo(event.provider());
            assertThat(build.installationId()).isEqualTo(event.installationId());
            assertThat(build.repoId()).isEqualTo(event.repoId());
            assertThat(build.repo()).isEqualTo(event.repo());
            assertThat(build.rootDirectory()).isEqualTo(event.rootDirectory());
            assertThat(build.headCommitMessage()).isEqualTo(event.headCommitMessage());
            assertThat(build.headCommitAuthor()).isEqualTo(event.headCommitAuthor());
            assertThat(build.traceparent()).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
            assertThat(build.attempt()).isZero();
            assertThat(build.enqueuedAt()).isNotNull();
            assertThat(build.statusReason()).isNull();
            assertThat(build.supersededBy()).isNull();
            assertThat(build.finishedAt()).isNull();

            assertThat(wakeups.await(NOTIFY_WAIT)).contains(DispatchSignal.CHANNEL);

            List<Received> records = queued.awaitKey(orgId, 1);
            assertThat(records).hasSize(1);
            assertThat(records.getFirst().header(EventHeaders.EVENT_ID))
                    .isEqualTo(BuildIds.queuedEventId(buildId).toString());
            JsonNode published = driver.json(records.getFirst().value());
            assertThat(IntakeDriver.text(published, "eventId"))
                    .isEqualTo(BuildIds.queuedEventId(buildId).toString());
            assertThat(IntakeDriver.text(published, "eventType")).isEqualTo(BuildQueued.TYPE);
            assertThat(IntakeDriver.text(published, "buildId")).isEqualTo(buildId.toString());
            assertThat(IntakeDriver.text(published, "appId")).isEqualTo(event.appId());
            assertThat(IntakeDriver.text(published, "branch")).isEqualTo(event.branch());
            assertThat(IntakeDriver.text(published, "commitSha")).isEqualTo(event.commitSha());
            assertThat(IntakeDriver.text(published, "pushEventId"))
                    .isEqualTo(event.eventId().toString());
            assertThat(IntakeDriver.text(published, "trigger")).isEqualTo(GitPushReceived.TRIGGER_MANUAL);
            assertThat(IntakeDriver.text(published, "supersedes")).isNull();
        }
    }

    @Test
    void theSameEventTwiceIsOneBuildAndOneBuildQueued() {
        String orgId = newOrg();
        GitPushReceived event = push(orgId, newApp()).build();

        driver.publish(event, event);
        driver.awaitProcessed();

        assertThat(driver.buildCount(orgId)).isOne();
        assertThat(driver.claims(event)).isOne();
        assertThat(driver.outbox(orgId, BuildQueued.TYPE)).hasSize(1);
    }

    @Test
    void aReplayedPushWhoseInboxClaimWasPurgedFindsItsBuildAlreadyRecorded() {
        String orgId = newOrg();
        GitPushReceived event = push(orgId, newApp()).build();
        driver.publish(event);
        driver.awaitBuild(event, BuildStatus.QUEUED);
        jdbc.update(
                "DELETE FROM build_queue.processed_events WHERE consumer = ? AND event_id = ?",
                PushListener.CONSUMER,
                event.eventId());

        driver.publish(event);
        driver.awaitProcessed();

        assertThat(driver.claims(event)).isOne();
        assertThat(driver.buildCount(orgId)).isOne();
        assertThat(driver.outbox(orgId, BuildQueued.TYPE)).hasSize(1);
    }

    @Test
    void anUnknownAppIsLearnedUnderThePushesOrgAndItsBuildQueued() {
        String orgId = newOrg();
        String appId = newApp();
        GitPushReceived event = push(orgId, appId).build();

        driver.publish(event);

        driver.awaitBuild(event, BuildStatus.QUEUED);
        AppProjection app = apps.findByOrgIdAndAppId(orgId, appId).orElseThrow();
        assertThat(app.status()).isEqualTo(AppProjection.Status.ACTIVE);
        assertThat(app.slug()).isNull();
        assertThat(orgs.findByOrgId(orgId)).isPresent();
    }

    @Test
    void pushesForADeletedAppOrADeletedOrgAreAcknowledgedWithoutABuild() {
        String liveOrg = fixtures.newOrg();
        GitPushReceived toDeletedApp =
                push(liveOrg, fixtures.newApp(liveOrg, "DELETED")).build();
        String deletedOrg = fixtures.newOrg();
        GitPushReceived toDeletedOrg =
                push(deletedOrg, fixtures.newApp(deletedOrg, "ACTIVE")).build();
        fixtures.deleteOrg(deletedOrg);

        driver.publish(toDeletedApp, toDeletedOrg);
        driver.awaitProcessed();

        assertThat(driver.claims(toDeletedApp)).isOne();
        assertThat(driver.claims(toDeletedOrg)).isOne();
        assertThat(driver.buildCount(liveOrg)).isZero();
        assertThat(driver.buildCount(deletedOrg)).isZero();
        assertThat(driver.outboxCount(liveOrg)).isZero();
        assertThat(driver.outboxCount(deletedOrg)).isZero();
    }

    /**
     * Driven in-process: a pushed event may be applied by any live test context, so only a direct call is sure to count
     * in this context's registry.
     */
    @Test
    void intakeCountsEveryOutcomeByReasonOrTrigger() {
        String liveOrg = fixtures.newOrg();
        String deletedOrg = fixtures.newOrg();
        GitPushReceived toDeletedOrg =
                push(deletedOrg, fixtures.newApp(deletedOrg, "ACTIVE")).build();
        fixtures.deleteOrg(deletedOrg);
        GitPushReceived toDeletedApp =
                push(liveOrg, fixtures.newApp(liveOrg, "DELETED")).build();
        GitPushReceived accepted = push(liveOrg, fixtures.newApp(liveOrg, "ACTIVE"))
                .trigger(GitPushReceived.TRIGGER_RECONCILED)
                .build();
        double orgDropsBefore = count(QueueMetrics.REQUESTS_DROPPED, QueueMetrics.TAG_REASON, "org_deleted");
        double appDropsBefore = count(QueueMetrics.REQUESTS_DROPPED, QueueMetrics.TAG_REASON, "app_deleted");
        double queuedBefore = queued(GitPushReceived.TRIGGER_RECONCILED);

        accept(toDeletedOrg);
        accept(toDeletedApp);
        accept(accepted);

        assertThat(count(QueueMetrics.REQUESTS_DROPPED, QueueMetrics.TAG_REASON, "org_deleted"))
                .isEqualTo(orgDropsBefore + 1);
        assertThat(count(QueueMetrics.REQUESTS_DROPPED, QueueMetrics.TAG_REASON, "app_deleted"))
                .isEqualTo(appDropsBefore + 1);
        assertThat(queued(GitPushReceived.TRIGGER_RECONCILED)).isEqualTo(queuedBefore + 1);
    }

    @Test
    void anAppKnownUnderAnotherOrgIsDeadLetteredAndNothingIsWritten() {
        String owner = fixtures.newOrg();
        String appId = fixtures.newApp(owner, "ACTIVE");
        String intruder = newOrg();
        GitPushReceived hostile = push(intruder, appId).build();

        try (TopicProbe deadLetters =
                new TopicProbe(kafka.getBootstrapServers(), Topics.deadLetter(Topics.GIT_PUSH_RECEIVED))) {
            driver.publish(hostile);

            assertThat(deadLetters.awaitKey(intruder, 1)).isNotEmpty();
        }
        driver.awaitProcessed();
        assertThat(driver.claims(hostile)).isZero();
        assertThat(driver.buildCount(intruder)).isZero();
        assertThat(driver.buildCount(owner)).isZero();
        assertThat(orgs.findByOrgId(intruder)).isEmpty();
        assertThat(apps.findByOrgIdAndAppId(owner, appId)).isPresent();
    }

    @Test
    void malformedPushesAreDeadLetteredOnTheirFirstDelivery() {
        String orgId = newOrg();
        GitPushReceived badSha = push(orgId, newApp()).commitSha("NOT-A-SHA").build();
        GitPushReceived unknownTrigger =
                push(orgId, newApp()).trigger("SCHEDULED").build();
        Duration firstRetryDelay = messaging.retry().initialInterval();

        try (TopicProbe deadLetters =
                new TopicProbe(kafka.getBootstrapServers(), Topics.deadLetter(Topics.GIT_PUSH_RECEIVED))) {
            driver.publish(badSha, unknownTrigger);

            List<Received> records = deadLetters.awaitKey(orgId, 2);
            assertThat(records).hasSizeGreaterThanOrEqualTo(2);
            assertThat(records).allSatisfy(record -> {
                assertThat(List.of(
                                String.valueOf(record.header(KafkaHeaders.DLT_EXCEPTION_FQCN)),
                                String.valueOf(record.header(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))))
                        .contains(MalformedPushException.class.getName());
                assertThat(Duration.ofMillis(record.timestamp() - originalTimestamp(record)))
                        .as("dead-lettered before any retry backoff elapsed")
                        .isLessThan(firstRetryDelay);
            });
        }
        assertThat(driver.claims(badSha)).isZero();
        assertThat(driver.claims(unknownTrigger)).isZero();
        assertThat(driver.buildCount(orgId)).isZero();
        assertThat(orgs.findByOrgId(orgId)).isEmpty();
    }

    @Test
    void aFailureAtCommitLeavesNoBuildNoEventAndNoWakeup() {
        failCommitsForOrgsStartingWith(COMMIT_FAILS_PREFIX);
        String orgId = COMMIT_FAILS_PREFIX + UUID.randomUUID();
        GitPushReceived event = push(orgId, newApp()).build();

        try (PgNotifications wakeups = new PgNotifications(postgres, DispatchSignal.CHANNEL);
                TopicProbe deadLetters =
                        new TopicProbe(kafka.getBootstrapServers(), Topics.deadLetter(Topics.GIT_PUSH_RECEIVED))) {
            driver.publish(event);

            assertThat(deadLetters.awaitKey(orgId, 1)).isNotEmpty();
            assertThat(wakeups.await(SILENCE)).isEmpty();
        }
        assertThat(driver.buildCount(orgId)).isZero();
        assertThat(driver.outboxCount(orgId)).isZero();
        assertThat(driver.claims(event)).isZero();
        assertThat(orgs.findByOrgId(orgId)).isEmpty();
    }

    /** A deferred constraint trigger raises at commit, after intake has inserted, appended and notified. */
    private void failCommitsForOrgsStartingWith(String prefix) {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION build_queue.fail_at_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'commit failure injected by test';
                END $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER fail_at_commit AFTER INSERT ON build_queue.builds
                    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
                    WHEN (NEW.org_id LIKE '%s%%')
                    EXECUTE FUNCTION build_queue.fail_at_commit()
                """.formatted(prefix));
    }

    private void accept(GitPushReceived push) {
        transaction.executeWithoutResult(status -> intake.accept(PushValidator.validate(push, null)));
    }

    private double queued(String trigger) {
        Counter counter = meterRegistry
                .find(QueueMetrics.REQUESTS)
                .tag(QueueMetrics.TAG_OUTCOME, "queued")
                .tag(QueueMetrics.TAG_TRIGGER, trigger)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private double count(String name, String tag, String value) {
        Counter counter = meterRegistry.find(name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }

    private static long originalTimestamp(Received record) {
        return ByteBuffer.wrap(record.headers()
                        .lastHeader(KafkaHeaders.DLT_ORIGINAL_TIMESTAMP)
                        .value())
                .getLong();
    }

    private static String newOrg() {
        return "org-" + UUID.randomUUID();
    }

    private static String newApp() {
        return "app-" + UUID.randomUUID();
    }
}
