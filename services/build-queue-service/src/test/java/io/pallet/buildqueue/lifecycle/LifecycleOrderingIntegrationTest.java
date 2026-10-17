package io.pallet.buildqueue.lifecycle;

import static io.pallet.buildqueue.support.PushEvents.push;
import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.buildqueue.build.Build;
import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.dispatch.Dispatcher;
import io.pallet.buildqueue.lifecycle.LifecycleAnomalies.Kind;
import io.pallet.buildqueue.support.StubBuilder;
import io.pallet.common.events.BuildCancelled;
import io.pallet.common.events.BuildStarted;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.testcontainers.kafka.KafkaContainer;

/** Reports arrive late, early, twice, or for builds that never left the queue; status still only moves forward. */
@IntegrationTest
@Import({RedisTestContainerConfiguration.class, StubBuilder.class})
class LifecycleOrderingIntegrationTest {

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
    private KafkaContainer kafka;

    private LifecycleDriver driver;
    private Map<Kind, Double> before;

    @BeforeEach
    void setUp() {
        driver = new LifecycleDriver(publisher, builds, jdbc, dispatcher, listeners, kafka.getBootstrapServers());
        driver.quiesce();
        driver.awaitReportsProcessed();
        before = new EnumMap<>(Kind.class);
        for (Kind kind : Kind.values()) {
            before.put(kind, LifecycleDriver.anomalies(kind));
        }
    }

    @AfterEach
    void tearDown() {
        driver.quiesce();
        driver.close();
    }

    @Test
    void aResultBeforeItsStartFinishesTheBuildAndTheStartChangesNothing() {
        GitPushReceived push = push(newOrg(), newApp()).build();
        Build build = driver.dispatch(push);

        driver.report(driver.succeeded(build));
        Build succeeded = driver.awaitStatus(push, BuildStatus.SUCCEEDED);
        driver.report(driver.started(build));
        driver.awaitReportsProcessed();

        Build after = driver.build(push);
        assertThat(after.status()).isEqualTo(BuildStatus.SUCCEEDED);
        assertThat(after.startedAt()).isNull();
        assertThat(after.leaseExpiresAt()).isNull();
        assertThat(after.version()).isEqualTo(succeeded.version());
        assertCounted(Kind.OUT_OF_ORDER);
    }

    @Test
    void aSecondResultChangesNothing() {
        GitPushReceived push = push(newOrg(), newApp()).build();
        Build build = driver.dispatch(push);
        driver.report(driver.succeeded(build));
        Build succeeded = driver.awaitStatus(push, BuildStatus.SUCCEEDED);

        driver.report(driver.failed(build, "too late"));
        driver.awaitReportsProcessed();

        Build after = driver.build(push);
        assertThat(after.status()).isEqualTo(BuildStatus.SUCCEEDED);
        assertThat(after.statusReason()).isNull();
        assertThat(after.finishedAt()).isEqualTo(succeeded.finishedAt());
        assertCounted(Kind.OUT_OF_ORDER);
    }

    @Test
    void aSecondStartChangesNothing() {
        GitPushReceived push = push(newOrg(), newApp()).build();
        Build build = driver.dispatch(push);
        driver.report(driver.started(build));
        Build running = driver.awaitStatus(push, BuildStatus.RUNNING);

        driver.report(driver.started(build));
        driver.awaitReportsProcessed();

        Build after = driver.build(push);
        assertThat(after.leaseExpiresAt()).isEqualTo(running.leaseExpiresAt());
        assertThat(after.version()).isEqualTo(running.version());
        assertCounted(Kind.OUT_OF_ORDER);
    }

    @Test
    void aResultForASupersededBuildIsImpossible() {
        String orgId = newOrg();
        String appId = newApp();
        GitPushReceived older = push(orgId, appId).build();
        GitPushReceived newer = push(orgId, appId).build();
        driver.publish(older, newer);
        driver.awaitStatus(newer, BuildStatus.QUEUED);
        Build superseded = driver.awaitStatus(older, BuildStatus.SUPERSEDED);

        driver.report(driver.succeeded(superseded));
        driver.awaitReportsProcessed();

        assertThat(driver.build(older).status()).isEqualTo(BuildStatus.SUPERSEDED);
        assertThat(driver.build(newer).status()).isEqualTo(BuildStatus.QUEUED);
        assertCounted(Kind.IMPOSSIBLE);
    }

    @Test
    void aReportForAnUnknownBuildIsUntracked() {
        driver.report(
                BuildStarted.of(newOrg(), UUID.randomUUID().toString(), newApp(), "0".repeat(40), UUID.randomUUID()));
        driver.awaitReportsProcessed();

        assertCounted(Kind.UNTRACKED);
    }

    @Test
    void aLateResultFinishesAnExpiredBuild() {
        GitPushReceived push = push(newOrg(), newApp()).build();
        Build build = driver.dispatch(push);
        driver.report(driver.started(build));
        driver.awaitStatus(push, BuildStatus.RUNNING);
        driver.expire(build, BuildCancelled.REASON_LEASE_EXPIRED);

        driver.report(driver.succeeded(build));

        Build succeeded = driver.awaitStatus(push, BuildStatus.SUCCEEDED);
        assertThat(succeeded.finishedAt()).isNotNull();
        driver.awaitReportsProcessed();
        assertCounted(Kind.LATE_RESULT);
    }

    @Test
    void aLateStartLeavesAnExpiredBuildOutOfFlight() {
        GitPushReceived push = push(newOrg(), newApp()).build();
        Build build = driver.dispatch(push);
        driver.expire(build, BuildCancelled.REASON_START_TIMEOUT);
        Build expired = driver.build(push);

        driver.report(driver.started(build));
        driver.awaitReportsProcessed();

        Build after = driver.build(push);
        assertThat(after.status()).isEqualTo(BuildStatus.EXPIRED);
        assertThat(after.startedAt()).isNull();
        assertThat(after.version()).isEqualTo(expired.version());
        assertCounted(Kind.LATE_START);
    }

    /** Exactly one anomaly, of {@code kind}, since the test began. */
    private void assertCounted(Kind kind) {
        for (Kind each : Kind.values()) {
            assertThat(LifecycleDriver.anomalies(each))
                    .as(each.name())
                    .isEqualTo(before.get(each) + (each == kind ? 1 : 0));
        }
    }

    private static String newOrg() {
        return "org-" + UUID.randomUUID();
    }

    private static String newApp() {
        return "app-" + UUID.randomUUID();
    }
}
