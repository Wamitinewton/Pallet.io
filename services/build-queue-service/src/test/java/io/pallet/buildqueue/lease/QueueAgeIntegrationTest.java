package io.pallet.buildqueue.lease;

import static io.pallet.buildqueue.support.PushEvents.push;
import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.buildqueue.build.Build;
import io.pallet.buildqueue.build.BuildIds;
import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.config.BuildQueueProperties;
import io.pallet.common.events.BuildCancelled;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.PlatformEventPublisher;
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

/** Sweeps by hand, as an instance with dispatch paused, so nothing but the queue age can move the build. */
@LeaseTestContext
class QueueAgeIntegrationTest {

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
    private ApplicationContext context;

    private LeaseDriver driver;
    private LeaseSweeper pausedSweeper;

    @BeforeEach
    void setUp() {
        driver = new LeaseDriver(publisher, builds, jdbc, jsonMapper, kafka.getBootstrapServers());
        driver.quiesce();
        pausedSweeper = LeaseSweepers.withDispatchPaused(context);
    }

    @AfterEach
    void tearDown() {
        driver.quiesce();
    }

    @Test
    void aBuildWaitingLongerThanMaxAgeExpiresOnceWithQueueTimeout() {
        GitPushReceived push =
                push("org-" + UUID.randomUUID(), "app-" + UUID.randomUUID()).build();
        driver.publish(push);
        Build queued = driver.awaitStatus(push, BuildStatus.QUEUED);

        pausedSweeper.sweep();
        assertThat(driver.build(push).status()).isEqualTo(BuildStatus.QUEUED);

        driver.awaitDatabaseTimePast(queued.enqueuedAt().plus(properties.queue().maxAge()));
        pausedSweeper.sweep();

        Build expired = driver.build(push);
        assertThat(expired.status()).isEqualTo(BuildStatus.EXPIRED);
        assertThat(expired.statusReason()).isEqualTo(BuildCancelled.REASON_QUEUE_TIMEOUT);
        assertThat(expired.attempt()).isZero();
        assertThat(expired.finishedAt())
                .isAfter(queued.enqueuedAt().plus(properties.queue().maxAge()));

        pausedSweeper.sweep();
        assertThat(driver.build(push).version()).isEqualTo(expired.version());
        assertThat(driver.outboxed(BuildIds.cancelledEventId(expired.buildId())))
                .isOne();
        List<JsonNode> cancelled = driver.published(Topics.BUILD_CANCELLED, push, 1);
        assertThat(cancelled)
                .singleElement()
                .satisfies(event ->
                        assertThat(event.path("reason").asString()).isEqualTo(BuildCancelled.REASON_QUEUE_TIMEOUT));
    }
}
