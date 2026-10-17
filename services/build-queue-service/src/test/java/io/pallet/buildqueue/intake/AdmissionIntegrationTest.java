package io.pallet.buildqueue.intake;

import static io.pallet.buildqueue.intake.IntakeDriver.buildIdOf;
import static io.pallet.buildqueue.intake.IntakeDriver.text;
import static io.pallet.buildqueue.support.PushEvents.push;
import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.buildqueue.build.Build;
import io.pallet.buildqueue.build.BuildIds;
import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.dispatch.LimitProfiles;
import io.pallet.buildqueue.support.TopicProbe;
import io.pallet.common.events.BuildCancelled;
import io.pallet.common.events.BuildQueued;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.events.Topics;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Both orgs use the {@code tight} profile ({@code max-queued: 3}) from {@code application-test.yml}. */
@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class AdmissionIntegrationTest {

    private static final String FULL_ORG = "org-admission-full";
    private static final String NEIGHBOUR_ORG = "org-admission-neighbour";
    private static final int MAX_QUEUED = 3;

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private BuildRepository builds;

    @Autowired
    private LimitProfiles limits;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private KafkaContainer kafka;

    private IntakeDriver driver;

    @BeforeEach
    void setUp() {
        driver = new IntakeDriver(publisher, builds, jdbc, jsonMapper, listeners, kafka.getBootstrapServers());
        removeOrgs();
    }

    @AfterEach
    void tearDown() {
        driver.close();
        removeOrgs();
    }

    @Test
    void theTestOrgsRunUnderTheTightProfile() {
        assertThat(limits.forOrg(FULL_ORG).maxQueued()).isEqualTo(MAX_QUEUED);
        assertThat(limits.forOrg(NEIGHBOUR_ORG).maxQueued()).isEqualTo(MAX_QUEUED);
    }

    @Test
    void anOrgAtItsCapGetsTheNewestRequestRejectedAndNothingElse() {
        List<GitPushReceived> waiting = fillQueue(FULL_ORG);
        GitPushReceived overflow = push(FULL_ORG, newApp()).build();

        try (TopicProbe cancelled = new TopicProbe(kafka.getBootstrapServers(), Topics.BUILD_CANCELLED)) {
            driver.publish(overflow);
            driver.awaitProcessed();

            Build rejected = driver.build(overflow).orElseThrow();
            assertThat(rejected.status()).isEqualTo(BuildStatus.REJECTED);
            assertThat(rejected.statusReason()).isEqualTo(BuildCancelled.REASON_QUEUE_FULL);
            assertThat(rejected.finishedAt()).isNotNull();
            assertThat(waiting)
                    .allSatisfy(push -> assertThat(driver.build(push))
                            .get()
                            .extracting(Build::status)
                            .isEqualTo(BuildStatus.QUEUED));
            assertThat(driver.outbox(FULL_ORG, BuildQueued.TYPE))
                    .extracting(event -> text(event, "buildId"))
                    .doesNotContain(buildIdOf(overflow).toString());

            JsonNode event = driver.json(cancelled
                    .awaitFirst(
                            FULL_ORG,
                            record ->
                                    buildIdOf(overflow).toString().equals(text(driver.json(record.value()), "buildId")))
                    .value());
            assertThat(text(event, "eventId"))
                    .isEqualTo(BuildIds.cancelledEventId(buildIdOf(overflow)).toString());
            assertThat(text(event, "reason")).isEqualTo(BuildCancelled.REASON_QUEUE_FULL);
            assertThat(text(event, "supersededBy")).isNull();
        }
    }

    @Test
    void aPushThatReplacesAWaitingBuildIsAdmittedAtTheCap() {
        List<GitPushReceived> waiting = fillQueue(FULL_ORG);
        GitPushReceived replaced = waiting.getFirst();
        GitPushReceived newer = push(FULL_ORG, replaced.appId()).build();

        driver.publish(newer);
        driver.awaitProcessed();

        assertThat(driver.build(newer)).get().extracting(Build::status).isEqualTo(BuildStatus.QUEUED);
        assertThat(driver.build(replaced)).get().extracting(Build::status).isEqualTo(BuildStatus.SUPERSEDED);
        assertThat(queuedCount(FULL_ORG)).isEqualTo(MAX_QUEUED);
    }

    @Test
    void aFullOrgLeavesAnotherOrgFreeToQueue() {
        fillQueue(FULL_ORG);
        GitPushReceived overflow = push(FULL_ORG, newApp()).build();
        GitPushReceived neighbour = push(NEIGHBOUR_ORG, newApp()).build();

        driver.publish(overflow, neighbour);
        driver.awaitProcessed();

        assertThat(driver.build(overflow)).get().extracting(Build::status).isEqualTo(BuildStatus.REJECTED);
        assertThat(driver.build(neighbour)).get().extracting(Build::status).isEqualTo(BuildStatus.QUEUED);
    }

    private List<GitPushReceived> fillQueue(String orgId) {
        List<GitPushReceived> pushes = List.of(
                push(orgId, newApp()).build(),
                push(orgId, newApp()).build(),
                push(orgId, newApp()).build());
        pushes.forEach(driver::publish);
        driver.awaitProcessed();
        assertThat(queuedCount(orgId)).isEqualTo(MAX_QUEUED);
        return pushes;
    }

    private int queuedCount(String orgId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM build_queue.builds WHERE org_id = ? AND status = 'QUEUED'", Integer.class, orgId);
    }

    /** The org ids are fixed by the profile mapping, so every test starts from an empty org. */
    private void removeOrgs() {
        for (String orgId : List.of(FULL_ORG, NEIGHBOUR_ORG)) {
            jdbc.update("DELETE FROM build_queue.builds WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM build_queue.apps WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM build_queue.orgs WHERE org_id = ?", orgId);
            jdbc.update("DELETE FROM build_queue.outbox_events WHERE org_id = ?", orgId);
        }
    }

    private static String newApp() {
        return "app-" + UUID.randomUUID();
    }
}
