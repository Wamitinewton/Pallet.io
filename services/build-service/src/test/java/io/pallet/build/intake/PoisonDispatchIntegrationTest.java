package io.pallet.build.intake;

import static io.pallet.build.support.Dispatches.dispatch;
import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.build.domain.Build;
import io.pallet.build.domain.BuildRepository;
import io.pallet.build.domain.BuildStatus;
import io.pallet.build.support.Dispatches;
import io.pallet.build.support.ReadModelFixtures;
import io.pallet.build.support.TopicProbe;
import io.pallet.common.events.BuildDispatched;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/** Dispatches no retry can make valid go straight to the dead-letter topic and leave nothing behind. */
@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class PoisonDispatchIntegrationTest {

    private static final String DLT = Topics.deadLetter(Topics.BUILD_DISPATCHED);

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private BuildRepository builds;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private KafkaContainer kafka;

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
    void aMalformedDispatchIsDeadLetteredUnclaimed() {
        String orgId = fixtures.newOrg();
        BuildDispatched malformed = dispatch(orgId, fixtures.newApp(orgId, "ACTIVE"))
                .commitSha("not-a-sha")
                .build();

        assertDeadLettered(malformed);

        assertThat(driver.find(malformed)).isEmpty();
        assertThat(driver.outboxCount(orgId)).isZero();
    }

    @Test
    void aDispatchAboveTheDurationCapIsDeadLettered() {
        String orgId = fixtures.newOrg();
        BuildDispatched tooLong = dispatch(orgId, fixtures.newApp(orgId, "ACTIVE"))
                .maxDurationSeconds(Long.MAX_VALUE)
                .build();

        assertDeadLettered(tooLong);

        assertThat(driver.find(tooLong)).isEmpty();
    }

    @Test
    void aDispatchForAnotherOrgsAppIsDeadLetteredAndNothingIsRecorded() {
        String owner = fixtures.newOrg();
        String intruder = fixtures.newOrg();
        String appId = fixtures.newApp(owner, "ACTIVE");
        BuildDispatched foreign = dispatch(intruder, appId).build();

        assertDeadLettered(foreign);

        assertThat(driver.rowCount(foreign.buildId())).isZero();
        assertThat(jdbc.queryForObject("SELECT org_id FROM build.apps WHERE app_id = ?", String.class, appId))
                .isEqualTo(owner);
    }

    @Test
    void aKnownBuildDispatchedWithAnotherCommitIsDeadLetteredAndTheBuildIsUnchanged() {
        String orgId = fixtures.newOrg();
        BuildDispatched original =
                dispatch(orgId, fixtures.newApp(orgId, "ACTIVE")).build();
        driver.publish(original);
        Build accepted = driver.awaitStatus(original, BuildStatus.ACCEPTED);

        BuildDispatched conflicting = dispatch(orgId, original.appId())
                .sameBuildAs(original)
                .attempt(2)
                .commitSha(Dispatches.randomSha())
                .build();

        assertDeadLettered(conflicting);

        assertUnchanged(original, accepted);
    }

    @Test
    void aKnownBuildDispatchedForAnotherAppIsDeadLettered() {
        String orgId = fixtures.newOrg();
        BuildDispatched original =
                dispatch(orgId, fixtures.newApp(orgId, "ACTIVE")).build();
        driver.publish(original);
        Build accepted = driver.awaitStatus(original, BuildStatus.ACCEPTED);

        BuildDispatched conflicting = dispatch(orgId, fixtures.newApp(orgId, "ACTIVE"))
                .buildId(original.buildId())
                .commitSha(original.commitSha())
                .attempt(2)
                .build();

        assertDeadLettered(conflicting);

        assertUnchanged(original, accepted);
    }

    @Test
    void aKnownBuildDispatchedForAnotherOrgIsDeadLettered() {
        String orgId = fixtures.newOrg();
        BuildDispatched original =
                dispatch(orgId, fixtures.newApp(orgId, "ACTIVE")).build();
        driver.publish(original);
        Build accepted = driver.awaitStatus(original, BuildStatus.ACCEPTED);
        String otherOrg = fixtures.newOrg();

        BuildDispatched conflicting = dispatch(otherOrg, fixtures.newApp(otherOrg, "ACTIVE"))
                .buildId(original.buildId())
                .commitSha(original.commitSha())
                .attempt(2)
                .build();

        assertDeadLettered(conflicting);

        assertUnchanged(original, accepted);
        assertThat(driver.outboxCount(otherOrg)).isZero();
    }

    private void assertDeadLettered(BuildDispatched dispatch) {
        try (TopicProbe deadLetters = new TopicProbe(kafka.getBootstrapServers(), DLT)) {
            driver.publish(dispatch);

            assertThat(deadLetters.awaitKey(dispatch.orgId(), 1)).isNotEmpty();
        }
        driver.awaitProcessed();
        assertThat(driver.claims(dispatch)).isZero();
    }

    private void assertUnchanged(BuildDispatched original, Build before) {
        Build after = driver.build(original);
        assertThat(after.attempt()).isEqualTo(before.attempt());
        assertThat(after.commitSha()).isEqualTo(original.commitSha());
        assertThat(after.appId()).isEqualTo(original.appId());
        assertThat(after.updatedAt()).isEqualTo(before.updatedAt());
        assertThat(driver.rowCount(original.buildId())).isOne();
    }
}
