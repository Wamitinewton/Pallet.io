package io.pallet.buildqueue.dispatch;

import static io.pallet.buildqueue.support.PushEvents.push;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.buildqueue.build.Build;
import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.config.BuildQueueProperties;
import io.pallet.buildqueue.observability.QueueMetrics;
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
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the wakeup loop for each test only (see {@link DispatchWakeups}). A one-minute poll makes the {@code NOTIFY} path
 * the only way a build goes out within a second.
 */
@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
@TestPropertySource(properties = "pallet.build-queue.dispatch.poll-interval=PT1M")
class DispatchWakeupIntegrationTest {

    private static final Duration NOTIFY_LATENCY = Duration.ofSeconds(1);
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
    private MeterRegistry meterRegistry;

    @Autowired
    private BuildQueueProperties properties;

    @Autowired
    private DispatchWakeup wakeup;

    private DispatchDriver driver;

    @BeforeEach
    void setUp() {
        driver = new DispatchDriver(publisher, builds, jdbc, jsonMapper);
        driver.quiesce();
        wakeup.start();
        await().atMost(WAIT).until(() -> listenConnected() == 1.0);
    }

    @AfterEach
    void tearDown() {
        wakeup.stop();
        driver.quiesce();
    }

    @Test
    void aCommittedPushIsDispatchedWithinASecondOfBeingQueued() {
        GitPushReceived event = push(newOrg(), newApp()).build();

        driver.publish(event);

        Build build = driver.awaitStatus(event, BuildStatus.DISPATCHED, WAIT);
        assertThat(Duration.between(build.enqueuedAt(), build.dispatchedAt())).isLessThan(NOTIFY_LATENCY);
    }

    @Test
    void aKilledListenConnectionIsReportedRecoveredAndMissesNothing() {
        Integer terminated = jdbc.queryForObject(
                "SELECT count(*) FILTER (WHERE pg_terminate_backend(pid)) FROM pg_stat_activity"
                        + " WHERE application_name = ?",
                Integer.class,
                DispatchWakeup.APPLICATION_NAME);
        assertThat(terminated).isOne();

        await().atMost(WAIT).pollInterval(Duration.ofMillis(10)).until(() -> listenConnected() == 0.0);
        GitPushReceived event = push(newOrg(), newApp()).build();
        driver.publish(event);

        Build build = driver.awaitStatus(
                event,
                BuildStatus.DISPATCHED,
                properties.dispatch().pollInterval().plus(WAIT));
        assertThat(Duration.between(build.enqueuedAt(), build.dispatchedAt()))
                .isLessThan(properties.dispatch().pollInterval());
        await().atMost(WAIT).until(() -> listenConnected() == 1.0);
    }

    private double listenConnected() {
        return meterRegistry.get(QueueMetrics.DISPATCH_LISTEN_CONNECTED).gauge().value();
    }

    private static String newOrg() {
        return "org-" + UUID.randomUUID();
    }

    private static String newApp() {
        return "app-" + UUID.randomUUID();
    }
}
