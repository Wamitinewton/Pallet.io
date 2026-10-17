package io.pallet.buildqueue.lease;

import static io.pallet.buildqueue.support.PushEvents.push;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.buildqueue.build.Build;
import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.config.BuildQueueProperties;
import io.pallet.buildqueue.dispatch.DispatchWakeups;
import io.pallet.buildqueue.lease.LeaseSweeper.Sweep;
import io.pallet.buildqueue.observability.QueueMetrics;
import io.pallet.buildqueue.support.StubBuilder;
import io.pallet.buildqueue.support.StubBuilder.Mode;
import io.pallet.common.events.BuildCancelled;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.messaging.PlatformEventPublisher;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Freezes the shared Kafka container with {@code docker pause} while a build runs past its lease. Sweeps are run by hand,
 * so the order of outage, recovery, result and expiry is the test's, not the scheduler's. A sweep also acts on builds
 * other test classes left in flight, so assertions read this test's build rather than the sweep's counts.
 */
@LeaseTestContext
class BackboneOutageIntegrationTest {

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
    private LeaseSweeper sweeper;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ApplicationContext context;

    private LeaseDriver driver;
    private boolean kafkaPaused;

    @BeforeEach
    void setUp() {
        driver = new LeaseDriver(publisher, builds, jdbc, jsonMapper, kafka.getBootstrapServers());
        driver.quiesce();
    }

    @AfterEach
    void tearDown() {
        resumeKafka();
        DispatchWakeups.of(context).stop();
        stubBuilder.stop();
        driver.quiesce();
    }

    @Test
    void aResultArrivingWithinTheGraceAfterAnOutageWinsOverTheLeaseThatPassedDuringIt() {
        GitPushReceived push = push(newOrg(), newApp()).build();
        Build running = runPastItsLeaseWhileKafkaIsPaused(push);

        Instant recovering = driver.databaseNow();
        recover();

        Build extended = driver.build(push);
        assertThat(extended.status()).isEqualTo(BuildStatus.RUNNING);
        assertThat(extended.leaseExpiresAt())
                .isAfter(running.leaseExpiresAt())
                .isAfterOrEqualTo(recovering.plus(properties.lease().grace()));

        driver.publish(driver.succeeded(extended));

        Build succeeded = driver.awaitStatus(push, BuildStatus.SUCCEEDED);
        assertThat(succeeded.statusReason()).isNull();
    }

    @Test
    void aBuildStillSilentAfterAnOutageExpiresOneGraceAfterRecoveryAndNotBefore() {
        GitPushReceived push = push(newOrg(), newApp()).build();
        runPastItsLeaseWhileKafkaIsPaused(push);

        Instant recovering = driver.databaseNow();
        recover();
        Build extended = driver.build(push);

        sweeper.sweep();
        assertThat(driver.build(push).status()).isEqualTo(BuildStatus.RUNNING);

        driver.awaitDatabaseTimePast(extended.leaseExpiresAt());
        sweeper.sweep();

        Build expired = driver.build(push);
        assertThat(expired.status()).isEqualTo(BuildStatus.EXPIRED);
        assertThat(expired.statusReason()).isEqualTo(BuildCancelled.REASON_LEASE_EXPIRED);
        assertThat(expired.finishedAt())
                .isAfter(extended.leaseExpiresAt())
                .isAfterOrEqualTo(recovering.plus(properties.lease().grace()));
    }

    /**
     * Starts the build, freezes Kafka, waits out the lease and sweeps twice: neither sweep may expire it, and the outage
     * is recorded once, from the first.
     */
    private Build runPastItsLeaseWhileKafkaIsPaused(GitPushReceived push) {
        stubBuilder.start(Mode.startOnly());
        DispatchWakeups.of(context).start();
        driver.publish(push);
        Build running = driver.awaitStatus(push, BuildStatus.RUNNING);
        DispatchWakeups.of(context).stop();
        double skipped = sweepsSkipped();

        pauseKafka();
        driver.awaitDatabaseTimePast(running.leaseExpiresAt());

        Sweep first = sweeper.sweep();
        assertThat(first.outcome()).isEqualTo(Sweep.Outcome.DEAF);
        Instant unreachableSince = driver.unreachableSince().orElseThrow();
        Sweep second = sweeper.sweep();
        assertThat(second.outcome()).isEqualTo(Sweep.Outcome.DEAF);

        assertThat(driver.unreachableSince()).contains(unreachableSince);
        assertThat(sweepsSkipped()).isEqualTo(skipped + 2);
        Build held = driver.build(push);
        assertThat(held.status()).isEqualTo(BuildStatus.RUNNING);
        assertThat(held.leaseExpiresAt()).isEqualTo(running.leaseExpiresAt());
        return running;
    }

    /** Thaws Kafka and sweeps until one hears it again, which must re-apply the grace rather than act on a deadline. */
    private void recover() {
        resumeKafka();
        Sweep recovered = await().atMost(WAIT).until(sweeper::sweep, sweep -> sweep.outcome() != Sweep.Outcome.DEAF);
        assertThat(recovered.outcome()).isEqualTo(Sweep.Outcome.RECOVERED);
        assertThat(recovered.deadlinesExtended()).isPositive();
        assertThat(recovered.leaseExpired()).isZero();
        assertThat(driver.unreachableSince()).isEmpty();
    }

    private double sweepsSkipped() {
        return meterRegistry.counter(QueueMetrics.LEASE_SWEEP_SKIPPED).count();
    }

    private void pauseKafka() {
        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
        kafkaPaused = true;
    }

    private void resumeKafka() {
        if (kafkaPaused) {
            kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
            kafkaPaused = false;
        }
    }

    private static String newOrg() {
        return "org-" + UUID.randomUUID();
    }

    private static String newApp() {
        return "app-" + UUID.randomUUID();
    }
}
