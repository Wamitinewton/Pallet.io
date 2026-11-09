package io.pallet.build.intake;

import static io.pallet.build.intake.IntakeDriver.awaitDispatches;
import static io.pallet.build.intake.IntakeDriver.dispatches;
import static io.pallet.build.support.Dispatches.dispatch;
import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.build.admission.AdmissionSignal;
import io.pallet.build.domain.Build;
import io.pallet.build.domain.BuildIds;
import io.pallet.build.domain.BuildIds.EventKind;
import io.pallet.build.domain.BuildRepository;
import io.pallet.build.domain.BuildStatus;
import io.pallet.build.domain.FailureCode;
import io.pallet.build.domain.Strategy;
import io.pallet.build.intake.IntakeMetrics.Outcome;
import io.pallet.build.projection.AppProjection;
import io.pallet.build.projection.AppProjectionRepository;
import io.pallet.build.support.PgNotifications;
import io.pallet.build.support.ReadModelFixtures;
import io.pallet.build.support.TopicProbe;
import io.pallet.build.support.TopicProbe.Received;
import io.pallet.common.events.BuildDispatched;
import io.pallet.common.events.BuildFailed;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.assertj.core.data.TemporalUnitWithinOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class DispatchIntakeIntegrationTest {

    private static final Duration NOTIFY_WAIT = Duration.ofSeconds(10);
    private static final Duration SILENCE = Duration.ofSeconds(2);
    private static final Duration IN_TIME = Duration.ofMinutes(5);
    private static final Duration TOO_LATE = Duration.ofSeconds(2);

    /** Postgres keeps microseconds, rounding rather than truncating the nanoseconds an {@code Instant} may carry. */
    private static final TemporalUnitWithinOffset STORED_PRECISION = new TemporalUnitWithinOffset(1, ChronoUnit.MICROS);

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private BuildRepository builds;

    @Autowired
    private AppProjectionRepository apps;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

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
    }

    @Test
    void anInTimeDispatchIsAcceptedWithTheSettingsSnapshotAndWakesAdmission() {
        String orgId = fixtures.newOrg();
        String appId = fixtures.newApp(orgId, "ACTIVE");
        fixtures.setSettings(orgId, appId, "BUILDPACKS", "docker/Dockerfile.prod");
        BuildDispatched event = dispatch(orgId, appId).startByIn(IN_TIME).build();
        double accepted = dispatches(Outcome.ACCEPTED);

        try (PgNotifications wakeups = new PgNotifications(postgres, AdmissionSignal.CHANNEL)) {
            driver.publish(event);

            Build build = driver.awaitStatus(event, BuildStatus.ACCEPTED);
            assertThat(build.orgId()).isEqualTo(orgId);
            assertThat(build.appId()).isEqualTo(appId);
            assertThat(build.attempt()).isEqualTo(1);
            assertThat(build.branch()).isEqualTo(event.branch());
            assertThat(build.commitSha()).isEqualTo(event.commitSha());
            assertThat(build.pushEventId()).isEqualTo(event.pushEventId());
            assertThat(build.trigger()).isEqualTo(event.trigger());
            assertThat(build.provider()).isEqualTo(event.provider());
            assertThat(build.installationId()).isEqualTo(event.installationId());
            assertThat(build.repoId()).isEqualTo(event.repoId());
            assertThat(build.repo()).isEqualTo(event.repo());
            assertThat(build.rootDirectory()).isEqualTo(event.rootDirectory());
            assertThat(build.startBy()).isCloseTo(event.startBy(), STORED_PRECISION);
            assertThat(build.maxDurationSeconds()).isEqualTo(event.maxDurationSeconds());
            assertThat(build.strategySetting()).isEqualTo(Strategy.BUILDPACKS);
            assertThat(build.dockerfilePath()).isEqualTo("docker/Dockerfile.prod");
            assertThat(build.strategy()).isNull();
            assertThat(build.jobName()).isEqualTo(BuildIds.jobName(event.buildId()));
            assertThat(build.dropCount()).isZero();
            assertThat(build.startedAt()).isNull();
            assertThat(build.finishedAt()).isNull();
            assertThat(build.traceparent()).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
            assertThat(wakeups.await(NOTIFY_WAIT)).contains(AdmissionSignal.CHANNEL);
        }
        assertThat(driver.claims(event)).isOne();
        assertThat(driver.outboxCount(orgId)).isZero();
        awaitDispatches(Outcome.ACCEPTED, accepted + 1);
    }

    @Test
    void anAppWithoutSettingsGetsTheDefaults() {
        String orgId = fixtures.newOrg();
        BuildDispatched event =
                dispatch(orgId, fixtures.newApp(orgId, "ACTIVE")).build();

        driver.publish(event);

        Build build = driver.awaitStatus(event, BuildStatus.ACCEPTED);
        assertThat(build.strategySetting()).isEqualTo(Strategy.AUTO);
        assertThat(build.dockerfilePath()).isEqualTo("Dockerfile");
    }

    @Test
    void aDispatchWithoutARootDirectoryBuildsFromTheRepositoryRoot() {
        String orgId = fixtures.newOrg();
        BuildDispatched event = dispatch(orgId, fixtures.newApp(orgId, "ACTIVE"))
                .rootDirectory(null)
                .build();

        driver.publish(event);

        assertThat(driver.awaitStatus(event, BuildStatus.ACCEPTED).rootDirectory())
                .isEmpty();
    }

    @Test
    void aDispatchPastItsStartMarginIsDroppedSilently() {
        String orgId = fixtures.newOrg();
        BuildDispatched event = dispatch(orgId, fixtures.newApp(orgId, "ACTIVE"))
                .startByIn(TOO_LATE)
                .build();
        double late = dispatches(Outcome.LATE);

        try (PgNotifications wakeups = new PgNotifications(postgres, AdmissionSignal.CHANNEL)) {
            driver.publish(event);

            Build build = driver.awaitStatus(event, BuildStatus.DROPPED);
            assertThat(build.dropCount()).isOne();
            assertThat(wakeups.await(SILENCE)).isEmpty();
        }
        assertThat(driver.outboxCount(orgId)).isZero();
        awaitDispatches(Outcome.LATE, late + 1);
    }

    @Test
    void theSameEventTwiceIsStoppedByTheInboxAndIsNotADuplicateDispatch() {
        String orgId = fixtures.newOrg();
        BuildDispatched event =
                dispatch(orgId, fixtures.newApp(orgId, "ACTIVE")).build();
        driver.publish(event);
        Build first = driver.awaitStatus(event, BuildStatus.ACCEPTED);
        driver.awaitProcessed();
        double duplicates = dispatches(Outcome.DUPLICATE);

        driver.publish(event);
        driver.awaitProcessed();

        assertThat(driver.rowCount(event.buildId())).isOne();
        assertThat(driver.claims(event)).isOne();
        assertThat(driver.build(event).updatedAt()).isEqualTo(first.updatedAt());
        assertThat(dispatches(Outcome.DUPLICATE)).isEqualTo(duplicates);
    }

    @Test
    void theSameBuildAndAttemptUnderANewEventIdIsADuplicate() {
        String orgId = fixtures.newOrg();
        BuildDispatched event =
                dispatch(orgId, fixtures.newApp(orgId, "ACTIVE")).build();
        driver.publish(event);
        Build first = driver.awaitStatus(event, BuildStatus.ACCEPTED);
        double duplicates = dispatches(Outcome.DUPLICATE);

        BuildDispatched again = dispatch(orgId, event.appId())
                .sameBuildAs(event)
                .startByIn(IN_TIME.multipliedBy(2))
                .build();
        driver.publish(again);

        awaitDispatches(Outcome.DUPLICATE, duplicates + 1);
        assertThat(driver.claims(again)).isOne();
        Build after = driver.build(event);
        assertThat(after.startBy()).isEqualTo(first.startBy());
        assertThat(after.updatedAt()).isEqualTo(first.updatedAt());
    }

    @Test
    void aLaterInTimeAttemptBringsADroppedBuildBackAndKeepsItsDropCount() {
        String orgId = fixtures.newOrg();
        BuildDispatched dropped = dispatch(orgId, fixtures.newApp(orgId, "ACTIVE"))
                .startByIn(TOO_LATE)
                .build();
        driver.publish(dropped);
        driver.awaitStatus(dropped, BuildStatus.DROPPED);
        double reaccepted = dispatches(Outcome.REACCEPTED);

        BuildDispatched retry = dispatch(orgId, dropped.appId())
                .sameBuildAs(dropped)
                .attempt(2)
                .startByIn(IN_TIME)
                .build();
        try (PgNotifications wakeups = new PgNotifications(postgres, AdmissionSignal.CHANNEL)) {
            driver.publish(retry);

            Build build = driver.awaitStatus(retry, BuildStatus.ACCEPTED);
            assertThat(build.attempt()).isEqualTo(2);
            assertThat(build.startBy()).isCloseTo(retry.startBy(), STORED_PRECISION);
            assertThat(build.dropCount()).isOne();
            assertThat(wakeups.await(NOTIFY_WAIT)).contains(AdmissionSignal.CHANNEL);
        }
        awaitDispatches(Outcome.REACCEPTED, reaccepted + 1);
    }

    @Test
    void aLaterAttemptThatIsAlsoLateStaysDroppedAndCountsAnotherDrop() {
        String orgId = fixtures.newOrg();
        BuildDispatched dropped = dispatch(orgId, fixtures.newApp(orgId, "ACTIVE"))
                .startByIn(TOO_LATE)
                .build();
        driver.publish(dropped);
        driver.awaitStatus(dropped, BuildStatus.DROPPED);

        BuildDispatched retry = dispatch(orgId, dropped.appId())
                .sameBuildAs(dropped)
                .attempt(2)
                .startByIn(TOO_LATE)
                .build();
        driver.publish(retry);

        Build build = driver.awaitAttempt(retry, 2);
        assertThat(build.status()).isEqualTo(BuildStatus.DROPPED);
        assertThat(build.dropCount()).isEqualTo(2);
    }

    @Test
    void aLaterAttemptReplacesTheDeadlineOfAWaitingBuildButNotItsSettings() {
        String orgId = fixtures.newOrg();
        String appId = fixtures.newApp(orgId, "ACTIVE");
        BuildDispatched first = dispatch(orgId, appId).startByIn(IN_TIME).build();
        driver.publish(first);
        driver.awaitStatus(first, BuildStatus.ACCEPTED);
        fixtures.setSettings(orgId, appId, "DOCKERFILE", "build/Dockerfile");

        BuildDispatched retry = dispatch(orgId, appId)
                .sameBuildAs(first)
                .attempt(2)
                .startByIn(IN_TIME.multipliedBy(2))
                .build();
        driver.publish(retry);

        Build build = driver.awaitAttempt(retry, 2);
        assertThat(build.status()).isEqualTo(BuildStatus.ACCEPTED);
        assertThat(build.startBy()).isCloseTo(retry.startBy(), STORED_PRECISION);
        assertThat(build.strategySetting()).isEqualTo(Strategy.AUTO);
        assertThat(build.dockerfilePath()).isEqualTo("Dockerfile");
    }

    @Test
    void aLaterAttemptForAStartedBuildChangesNothing() {
        String orgId = fixtures.newOrg();
        BuildDispatched first =
                dispatch(orgId, fixtures.newApp(orgId, "ACTIVE")).build();
        driver.publish(first);
        driver.awaitStatus(first, BuildStatus.ACCEPTED);
        driver.moveToStarting(first);
        Build starting = driver.build(first);
        double duplicates = dispatches(Outcome.DUPLICATE);

        BuildDispatched retry = dispatch(orgId, first.appId())
                .sameBuildAs(first)
                .attempt(2)
                .startByIn(IN_TIME.multipliedBy(2))
                .build();
        driver.publish(retry);

        awaitDispatches(Outcome.DUPLICATE, duplicates + 1);
        Build after = driver.build(first);
        assertThat(after.status()).isEqualTo(BuildStatus.STARTING);
        assertThat(after.attempt()).isEqualTo(1);
        assertThat(after.startBy()).isEqualTo(starting.startBy());
        assertThat(after.updatedAt()).isEqualTo(starting.updatedAt());
    }

    @Test
    void anEarlierAttemptAfterALaterOneChangesNothing() {
        String orgId = fixtures.newOrg();
        String appId = fixtures.newApp(orgId, "ACTIVE");
        BuildDispatched second = dispatch(orgId, appId).attempt(2).build();
        driver.publish(second);
        Build accepted = driver.awaitStatus(second, BuildStatus.ACCEPTED);
        double duplicates = dispatches(Outcome.DUPLICATE);

        BuildDispatched first = dispatch(orgId, appId)
                .sameBuildAs(second)
                .attempt(1)
                .startByIn(IN_TIME.multipliedBy(2))
                .build();
        driver.publish(first);

        awaitDispatches(Outcome.DUPLICATE, duplicates + 1);
        Build after = driver.build(second);
        assertThat(after.attempt()).isEqualTo(2);
        assertThat(after.startBy()).isEqualTo(accepted.startBy());
    }

    @Test
    void aDispatchForADeletedAppFailsAtOnceWithOneDeterministicBuildFailed() {
        String orgId = fixtures.newOrg();
        String appId = fixtures.newApp(orgId, "DELETED");
        BuildDispatched event = dispatch(orgId, appId).build();
        double tornDown = dispatches(Outcome.TORN_DOWN);

        try (TopicProbe failed = new TopicProbe(kafka.getBootstrapServers(), Topics.BUILD_FAILED)) {
            driver.publish(event);

            Build build = driver.awaitStatus(event, BuildStatus.FAILED);
            assertThat(build.failureCode()).isEqualTo(FailureCode.APP_DELETED);
            assertThat(build.failureReason()).isEqualTo(FailureCode.APP_DELETED.reason());
            assertThat(build.startedAt()).isNull();
            assertThat(build.finishedAt()).isNotNull();

            List<Received> published = failed.awaitKey(orgId, 1);
            assertThat(published).hasSize(1);
            JsonNode payload = jsonMapper.readTree(published.getFirst().value());
            assertThat(payload.path("eventId").asString())
                    .isEqualTo(
                            BuildIds.eventId(event.buildId(), EventKind.FAILED).toString());
            assertThat(payload.path("buildId").asString()).isEqualTo(event.buildId());
            assertThat(payload.path("appId").asString()).isEqualTo(appId);
            assertThat(payload.path("commitSha").asString()).isEqualTo(event.commitSha());
            assertThat(payload.path("pushEventId").asString())
                    .isEqualTo(event.pushEventId().toString());
            assertThat(payload.path("branch").asString()).isEqualTo(event.branch());
            assertThat(payload.path("failureCode").asString()).isEqualTo(BuildFailed.CODE_APP_DELETED);
            assertThat(payload.path("reason").asString()).isEqualTo(FailureCode.APP_DELETED.reason());
            assertThat(payload.path("failedStep").isNull()).isTrue();
            assertThat(payload.path("durationMs").asLong()).isZero();
        }
        awaitDispatches(Outcome.TORN_DOWN, tornDown + 1);

        BuildDispatched redispatch =
                dispatch(orgId, appId).sameBuildAs(event).attempt(2).build();
        driver.publish(redispatch);
        driver.awaitProcessed();

        assertThat(driver.claims(redispatch)).isOne();
        assertThat(driver.outbox(orgId, BuildFailed.TYPE)).hasSize(1);
        assertThat(driver.build(event).attempt()).isEqualTo(1);
    }

    @Test
    void aDispatchForADeletedOrgFailsWithOrgDeleted() {
        String orgId = fixtures.newOrg();
        String appId = fixtures.newApp(orgId, "ACTIVE");
        fixtures.deleteOrg(orgId);
        BuildDispatched event = dispatch(orgId, appId).build();

        driver.publish(event);

        assertThat(driver.awaitStatus(event, BuildStatus.FAILED).failureCode()).isEqualTo(FailureCode.ORG_DELETED);
        assertThat(driver.outbox(orgId, BuildFailed.TYPE))
                .singleElement()
                .satisfies(payload ->
                        assertThat(payload.path("failureCode").asString()).isEqualTo(BuildFailed.CODE_ORG_DELETED));
    }

    @Test
    void aDispatchForAnUnknownAppRecordsTheAppUnderTheDispatchesOrg() {
        String orgId = "org-" + UUID.randomUUID();
        String appId = "app-" + UUID.randomUUID();
        BuildDispatched event = dispatch(orgId, appId).build();

        try {
            driver.publish(event);

            driver.awaitStatus(event, BuildStatus.ACCEPTED);
            assertThat(apps.findByOrgIdAndAppId(orgId, appId))
                    .get()
                    .extracting(AppProjection::status)
                    .isEqualTo(AppProjection.Status.ACTIVE);
        } finally {
            jdbc.update("DELETE FROM build.builds WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM build.apps WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM build.orgs WHERE org_id = ?", orgId);
        }
    }
}
