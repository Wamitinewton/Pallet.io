package io.pallet.buildqueue.lifecycle;

import static io.pallet.buildqueue.support.PushEvents.push;
import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.buildqueue.build.Build;
import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.build.BuildTransitions;
import io.pallet.buildqueue.config.BuildQueueProperties;
import io.pallet.buildqueue.dispatch.DispatchSignal;
import io.pallet.buildqueue.dispatch.Dispatcher;
import io.pallet.buildqueue.lifecycle.LifecycleAnomalies.Kind;
import io.pallet.buildqueue.support.PgNotifications;
import io.pallet.buildqueue.support.StubBuilder;
import io.pallet.buildqueue.support.StubBuilder.Mode;
import io.pallet.common.events.BuildSucceeded;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@IntegrationTest
@Import({RedisTestContainerConfiguration.class, StubBuilder.class})
class BuildLifecycleIntegrationTest {

    private static final Duration RUN_TIME = Duration.ofSeconds(2);
    private static final Duration NOTIFY_WAIT = Duration.ofSeconds(5);

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private BuildRepository builds;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Dispatcher dispatcher;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private BuildQueueProperties properties;

    @Autowired
    private StubBuilder stubBuilder;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private PostgreSQLContainer postgres;

    private LifecycleDriver driver;

    @BeforeEach
    void setUp() {
        driver = new LifecycleDriver(publisher, builds, jdbc, dispatcher, listeners, kafka.getBootstrapServers());
        driver.quiesce();
    }

    @AfterEach
    void tearDown() {
        stubBuilder.stop();
        driver.quiesce();
        driver.close();
    }

    @Test
    void aStartedBuildRunsUnderItsLeaseAndSucceeds() {
        stubBuilder.start(Mode.slow(RUN_TIME));
        GitPushReceived push = push(newOrg(), newApp()).build();

        Build dispatched = driver.dispatch(push);

        Build running = driver.awaitStatus(push, BuildStatus.RUNNING);
        assertThat(running.startedAt()).isNotNull();
        assertThat(running.leaseExpiresAt())
                .isEqualTo(running.startedAt()
                        .plus(properties.build().maxDuration())
                        .plus(properties.lease().grace()));
        assertThat(running.version()).isEqualTo(dispatched.version() + 1);

        Build succeeded = driver.awaitStatus(push, BuildStatus.SUCCEEDED);
        assertThat(succeeded.finishedAt()).isAfterOrEqualTo(running.startedAt());
        assertThat(succeeded.startedAt()).isEqualTo(running.startedAt());
        assertThat(succeeded.statusReason()).isNull();
        assertThat(succeeded.version()).isEqualTo(running.version() + 1);
    }

    @Test
    void aFailedBuildKeepsItsReason() {
        stubBuilder.start(Mode.fail());
        GitPushReceived push = push(newOrg(), newApp()).build();

        driver.dispatch(push);

        Build failed = driver.awaitStatus(push, BuildStatus.FAILED);
        assertThat(failed.statusReason()).isEqualTo(StubBuilder.FAILURE_REASON);
        assertThat(failed.startedAt()).isNotNull();
        assertThat(failed.finishedAt()).isNotNull();
    }

    @Test
    void aFailureReasonIsCutAtFiveHundredCharactersWithoutSplittingOne() {
        GitPushReceived push = push(newOrg(), newApp()).build();
        Build build = driver.dispatch(push);
        String reason = "é".repeat(BuildTransitions.MAX_FAILURE_REASON_LENGTH - 1) + "😀" + "x".repeat(100);

        driver.report(driver.failed(build, reason));

        Build failed = driver.awaitStatus(push, BuildStatus.FAILED);
        assertThat(failed.statusReason()).isEqualTo(reason.substring(0, reason.offsetByCodePoints(0, 500)));
        assertThat(failed.statusReason().codePointCount(0, failed.statusReason().length()))
                .isEqualTo(BuildTransitions.MAX_FAILURE_REASON_LENGTH);
        assertThat(failed.startedAt()).isNull();
    }

    @Test
    void aFailureWithoutAReasonIsStillApplied() {
        GitPushReceived push = push(newOrg(), newApp()).build();
        Build build = driver.dispatch(push);

        driver.report(driver.failed(build, null));

        assertThat(driver.awaitStatus(push, BuildStatus.FAILED).statusReason()).isNull();
    }

    @Test
    void theSameReportTwiceIsOneTransitionAndOneClaim() {
        GitPushReceived push = push(newOrg(), newApp()).build();
        Build build = driver.dispatch(push);
        BuildSucceeded succeeded = driver.succeeded(build);

        driver.report(succeeded, succeeded);
        driver.awaitReportsProcessed();

        Build after = driver.build(push);
        assertThat(after.status()).isEqualTo(BuildStatus.SUCCEEDED);
        assertThat(after.version()).isEqualTo(build.version() + 1);
        assertThat(driver.claims(BuildEventListener.SUCCEEDED_CONSUMER, succeeded.eventId()))
                .isOne();
    }

    @Test
    void aReportUnderAnotherOrgIsNeitherAppliedNorCountedAsThatOrgsBuild() {
        GitPushReceived push = push(newOrg(), newApp()).build();
        Build build = driver.dispatch(push);
        driver.awaitReportsProcessed();
        double untrackedBefore = LifecycleDriver.anomalies(Kind.UNTRACKED);

        driver.report(driver.started(build, newOrg()));
        driver.awaitReportsProcessed();

        Build after = driver.build(push);
        assertThat(after.status()).isEqualTo(BuildStatus.DISPATCHED);
        assertThat(after.version()).isEqualTo(build.version());
        assertThat(LifecycleDriver.anomalies(Kind.UNTRACKED)).isEqualTo(untrackedBefore + 1);
    }

    @Test
    void aReportUnderAnotherAppOfTheSameOrgIsNotApplied() {
        GitPushReceived push = push(newOrg(), newApp()).build();
        Build build = driver.dispatch(push);
        driver.awaitReportsProcessed();
        double untrackedBefore = LifecycleDriver.anomalies(Kind.UNTRACKED);

        driver.report(BuildSucceeded.of(
                build.orgId(), build.buildId().toString(), newApp(), build.commitSha(), build.pushEventId()));
        driver.awaitReportsProcessed();

        assertThat(driver.build(push).status()).isEqualTo(BuildStatus.DISPATCHED);
        assertThat(LifecycleDriver.anomalies(Kind.UNTRACKED)).isEqualTo(untrackedBefore + 1);
    }

    @Test
    void aResultWakesTheDispatcherButAStartDoesNot() {
        GitPushReceived push = push(newOrg(), newApp()).build();
        Build build = driver.dispatch(push);

        try (PgNotifications notifications = new PgNotifications(postgres, DispatchSignal.CHANNEL)) {
            driver.report(driver.started(build));
            driver.awaitStatus(push, BuildStatus.RUNNING);
            assertThat(notifications.await(Duration.ofMillis(500))).isEmpty();

            driver.report(driver.succeeded(build));
            driver.awaitStatus(push, BuildStatus.SUCCEEDED);
            assertThat(notifications.await(NOTIFY_WAIT)).containsExactly(DispatchSignal.CHANNEL);
        }
    }

    private static String newOrg() {
        return "org-" + UUID.randomUUID();
    }

    private static String newApp() {
        return "app-" + UUID.randomUUID();
    }
}
