package io.pallet.buildqueue.intake;

import static io.pallet.buildqueue.intake.IntakeDriver.buildIdOf;
import static io.pallet.buildqueue.intake.IntakeDriver.text;
import static io.pallet.buildqueue.support.PushEvents.push;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.pallet.buildqueue.build.Build;
import io.pallet.buildqueue.build.BuildIds;
import io.pallet.buildqueue.build.BuildRepository;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.build.PriorityClass;
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

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class CoalescingIntegrationTest {

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

    @BeforeEach
    void setUp() {
        driver = new IntakeDriver(publisher, builds, jdbc, jsonMapper, listeners, kafka.getBootstrapServers());
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    @Test
    void threePushesToOneBranchLeaveOnlyTheNewestWaitingEachSupersededByItsSuccessor() {
        String orgId = newOrg();
        String appId = newApp();
        GitPushReceived a = push(orgId, appId).build();
        GitPushReceived b = push(orgId, appId).build();
        GitPushReceived c = push(orgId, appId).build();

        try (TopicProbe queued = new TopicProbe(kafka.getBootstrapServers(), Topics.BUILD_QUEUED);
                TopicProbe cancelled = new TopicProbe(kafka.getBootstrapServers(), Topics.BUILD_CANCELLED)) {
            driver.publish(a, b, c);
            driver.awaitProcessed();

            assertSupersededBy(a, b);
            assertSupersededBy(b, c);
            assertThat(driver.build(c)).get().extracting(Build::status).isEqualTo(BuildStatus.QUEUED);

            assertThat(driver.outbox(orgId, BuildCancelled.TYPE))
                    .extracting(event -> text(event, "buildId"), event -> text(event, "supersededBy"))
                    .containsExactly(tuple(id(a), id(b)), tuple(id(b), id(c)));
            assertThat(driver.outbox(orgId, BuildQueued.TYPE))
                    .extracting(event -> text(event, "buildId"), event -> text(event, "supersedes"))
                    .containsExactly(tuple(id(a), null), tuple(id(b), id(a)), tuple(id(c), id(b)));

            assertThat(published(cancelled, orgId, 2))
                    .extracting(event -> text(event, "eventId"), event -> text(event, "reason"))
                    .containsExactly(
                            tuple(cancelledEventId(a), BuildCancelled.REASON_SUPERSEDED),
                            tuple(cancelledEventId(b), BuildCancelled.REASON_SUPERSEDED));
            assertThat(published(queued, orgId, 3))
                    .extracting(event -> text(event, "buildId"))
                    .containsExactly(id(a), id(b), id(c));
        }
    }

    @Test
    void eachBranchOfAnAppKeepsItsOwnWaitingBuild() {
        String orgId = newOrg();
        String appId = newApp();
        GitPushReceived main = push(orgId, appId).branch("main").build();
        GitPushReceived release = push(orgId, appId).branch("release").build();

        driver.publish(main, release);
        driver.awaitProcessed();

        assertThat(driver.build(main)).get().extracting(Build::status).isEqualTo(BuildStatus.QUEUED);
        assertThat(driver.build(release)).get().extracting(Build::status).isEqualTo(BuildStatus.QUEUED);
        assertThat(driver.outbox(orgId, BuildCancelled.TYPE)).isEmpty();
    }

    @Test
    void aManualPushReplacesAWaitingWebhookPushAndWaitsAsInteractive() {
        String orgId = newOrg();
        String appId = newApp();
        GitPushReceived webhook = push(orgId, appId).build();
        GitPushReceived manual =
                push(orgId, appId).trigger(GitPushReceived.TRIGGER_MANUAL).build();

        driver.publish(webhook, manual);
        driver.awaitProcessed();

        assertSupersededBy(webhook, manual);
        Build waiting = driver.build(manual).orElseThrow();
        assertThat(waiting.status()).isEqualTo(BuildStatus.QUEUED);
        assertThat(waiting.priorityClass()).isEqualTo(PriorityClass.INTERACTIVE);
        assertThat(waiting.priorityClass().rank()).isZero();
    }

    @Test
    void interleavedPushesForTwoAppsKeepOnlyEachAppsNewest() {
        String orgId = newOrg();
        String api = newApp();
        String web = newApp();
        GitPushReceived api1 = push(orgId, api).build();
        GitPushReceived web1 = push(orgId, web).build();
        GitPushReceived api2 = push(orgId, api).build();
        GitPushReceived web2 = push(orgId, web).build();
        GitPushReceived api3 = push(orgId, api).build();

        driver.publish(api1, web1, api2, web2, api3);
        driver.awaitProcessed();

        assertSupersededBy(api1, api2);
        assertSupersededBy(api2, api3);
        assertSupersededBy(web1, web2);
        assertThat(List.of(driver.build(api3).orElseThrow(), driver.build(web2).orElseThrow()))
                .extracting(Build::status)
                .containsOnly(BuildStatus.QUEUED);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM build_queue.builds WHERE org_id = ? AND status = 'QUEUED'",
                        Integer.class,
                        orgId))
                .isEqualTo(2);
    }

    private void assertSupersededBy(GitPushReceived older, GitPushReceived newer) {
        Build build = driver.build(older).orElseThrow();
        assertThat(build.status()).isEqualTo(BuildStatus.SUPERSEDED);
        assertThat(build.statusReason()).isEqualTo(BuildCancelled.REASON_SUPERSEDED);
        assertThat(build.supersededBy()).isEqualTo(buildIdOf(newer));
        assertThat(build.finishedAt()).isNotNull();
    }

    private List<JsonNode> published(TopicProbe probe, String orgId, int expected) {
        return probe.awaitKey(orgId, expected).stream()
                .map(record -> driver.json(record.value()))
                .toList();
    }

    private static String id(GitPushReceived push) {
        return buildIdOf(push).toString();
    }

    private static String cancelledEventId(GitPushReceived push) {
        return BuildIds.cancelledEventId(buildIdOf(push)).toString();
    }

    private static String newOrg() {
        return "org-" + UUID.randomUUID();
    }

    private static String newApp() {
        return "app-" + UUID.randomUUID();
    }
}
