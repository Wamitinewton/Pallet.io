package io.pallet.buildqueue.lease;

import static io.pallet.buildqueue.support.PushEvents.push;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.pallet.buildqueue.build.Build;
import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.config.BuildQueueProperties;
import io.pallet.buildqueue.dispatch.DispatchWakeups;
import io.pallet.buildqueue.support.Counters;
import io.pallet.buildqueue.support.StubBuilder;
import io.pallet.buildqueue.support.StubBuilder.Mode;
import io.pallet.common.events.BuildCancelled;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.PlatformEventPublisher;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/** The dispatch and sweep loops run for each test only, as in {@link StartDeadlineIntegrationTest}. */
@LeaseTestContext
class RunLeaseIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String ANOMALY = "build_queue.events.anomaly";

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
    void aBuildThatNeverReportsLosesItsSlotAtTheLeaseAndItsLateResultIsStillRecorded() {
        stubBuilder.start(Mode.startOnly());
        DispatchWakeups.of(context).start();
        sweepSchedule.start();
        String org = "org-" + UUID.randomUUID();
        String app = "app-" + UUID.randomUUID();
        GitPushReceived first = push(org, app).build();
        GitPushReceived next = push(org, app).build();

        driver.publish(first);
        driver.awaitStatus(first, BuildStatus.RUNNING);
        driver.publish(next);
        driver.awaitStatus(next, BuildStatus.QUEUED);

        Build expired = driver.awaitStatus(first, BuildStatus.EXPIRED);
        assertThat(expired.statusReason()).isEqualTo(BuildCancelled.REASON_LEASE_EXPIRED);
        assertThat(Duration.between(expired.startedAt(), expired.leaseExpiresAt()))
                .isEqualTo(
                        properties.build().maxDuration().plus(properties.lease().grace()));
        assertThat(expired.finishedAt()).isAfter(expired.leaseExpiresAt());
        assertThat(driver.published(Topics.BUILD_CANCELLED, first, 1))
                .singleElement()
                .satisfies(event ->
                        assertThat(event.path("reason").asString()).isEqualTo(BuildCancelled.REASON_LEASE_EXPIRED));
        await().atMost(WAIT).until(() -> driver.build(next).dispatchedAt() != null);

        double lateResults = Counters.count(ANOMALY, "kind", "late_result");
        driver.publish(driver.succeeded(expired));

        driver.awaitStatus(first, BuildStatus.SUCCEEDED);
        await().atMost(WAIT).until(() -> Counters.count(ANOMALY, "kind", "late_result") == lateResults + 1);
    }
}
