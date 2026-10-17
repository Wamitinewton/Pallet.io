package io.pallet.buildqueue.cancel;

import static io.pallet.buildqueue.cancel.CancelDriver.text;
import static io.pallet.buildqueue.support.PushEvents.push;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.pallet.buildqueue.build.BuildIds;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.dispatch.Dispatcher;
import io.pallet.buildqueue.dispatch.LimitProfiles;
import io.pallet.buildqueue.support.Counters;
import io.pallet.buildqueue.support.ReadModelFixtures;
import io.pallet.buildqueue.support.StubBuilder;
import io.pallet.buildqueue.support.StubBuilder.Mode;
import io.pallet.common.events.AppDeleted;
import io.pallet.common.events.BuildCancelled;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.events.OrgDeleted;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@Import({RedisTestContainerConfiguration.class, StubBuilder.class})
class TeardownIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Duration RUN_TIME = Duration.ofSeconds(4);
    private static final String DROPPED = "build_queue.requests.dropped";

    @Autowired
    private PlatformEventPublisher publisher;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private Dispatcher dispatcher;

    @Autowired
    private LimitProfiles limits;

    @Autowired
    private StubBuilder stubBuilder;

    private ReadModelFixtures fixtures;
    private CancelDriver driver;

    @BeforeEach
    void setUp() {
        fixtures = new ReadModelFixtures(jdbc);
        driver = new CancelDriver(jdbc, jsonMapper);
        driver.quiesce();
    }

    @AfterEach
    void tearDown() {
        stubBuilder.stop();
        driver.quiesce();
        fixtures.cleanUp();
    }

    @Test
    void deletingAnAppCancelsItsWaitingBuildsAndLeavesTheRunningOneToFinish() {
        stubBuilder.start(Mode.slow(RUN_TIME));
        String orgId = newOrg();
        String appId = newApp();
        UUID running = queue(push(orgId, appId).branch("main").build());
        dispatcher.runRound();
        awaitStatus(running, BuildStatus.RUNNING);
        UUID featureA = queue(push(orgId, appId).branch("feature-a").build());
        UUID featureB = queue(push(orgId, appId).branch("feature-b").build());

        publisher.publish(appDeleted(orgId, appId));

        for (UUID waiting : List.of(featureA, featureB)) {
            awaitStatus(waiting, BuildStatus.CANCELLED);
            assertThat(driver.statusReason(waiting)).isEqualTo(BuildCancelled.REASON_APP_DELETED);
            assertThat(driver.cancelledBy(waiting)).isNull();
            assertThat(driver.cancelledEvents(waiting)).singleElement().satisfies(event -> {
                assertThat(text(event, "reason")).isEqualTo(BuildCancelled.REASON_APP_DELETED);
                assertThat(text(event, "appId")).isEqualTo(appId);
            });
            assertThat(driver.auditEvents(waiting)).isEmpty();
        }
        assertThat(driver.status(running)).isEqualTo(BuildStatus.RUNNING);
        assertThat(driver.cancelledEvents(running)).isEmpty();

        awaitStatus(running, BuildStatus.SUCCEEDED);
    }

    @Test
    void aRedeliveredAppDeletionCancelsNothingTwice() {
        String orgId = newOrg();
        String appId = newApp();
        UUID waiting = queue(push(orgId, appId).build());
        AppDeleted deletion = appDeleted(orgId, appId);
        publisher.publish(deletion);
        awaitStatus(waiting, BuildStatus.CANCELLED);
        double droppedBefore = Counters.count(DROPPED, "reason", "app_deleted");

        publisher.publish(deletion);
        GitPushReceived marker = push(orgId, appId).build();
        publisher.publish(marker);

        await().atMost(WAIT).until(() -> Counters.count(DROPPED, "reason", "app_deleted") > droppedBefore);
        assertThat(driver.cancelledEvents(waiting)).hasSize(1);
        assertThat(driver.cancelledEventsOfOrg(orgId)).hasSize(1);
        assertThat(exists(BuildIds.buildId(marker.eventId()))).isFalse();
    }

    @Test
    void deletingAnOrgCancelsEveryWaitingBuildOfEveryAppAndDropsLaterPushes() {
        String orgId = newOrg();
        List<String> appIds = IntStream.range(0, 5).mapToObj(i -> newApp()).toList();
        List<UUID> queued = new ArrayList<>();
        for (String appId : appIds) {
            queued.add(queue(push(orgId, appId).branch("main").build()));
            queued.add(queue(push(orgId, appId).branch("develop").build()));
        }
        dispatcher.runRound();
        List<UUID> inFlight = queued.stream()
                .filter(buildId -> driver.status(buildId) == BuildStatus.DISPATCHED)
                .toList();
        assertThat(inFlight).hasSize(limits.forOrg(orgId).maxConcurrent());
        List<UUID> waiting =
                queued.stream().filter(buildId -> !inFlight.contains(buildId)).toList();

        publisher.publish(OrgDeleted.of(orgId, "user-1"));

        for (UUID buildId : waiting) {
            awaitStatus(buildId, BuildStatus.CANCELLED);
            assertThat(driver.statusReason(buildId)).isEqualTo(BuildCancelled.REASON_ORG_DELETED);
        }
        inFlight.forEach(buildId -> assertThat(driver.status(buildId)).isEqualTo(BuildStatus.DISPATCHED));
        assertThat(driver.cancelledEventsOfOrg(orgId))
                .hasSize(waiting.size())
                .allSatisfy(event -> assertThat(text(event, "reason")).isEqualTo(BuildCancelled.REASON_ORG_DELETED));

        double droppedBefore = Counters.count(DROPPED, "reason", "org_deleted");
        GitPushReceived late = push(orgId, appIds.getFirst()).build();
        publisher.publish(late);

        await().atMost(WAIT).until(() -> Counters.count(DROPPED, "reason", "org_deleted") > droppedBefore);
        assertThat(exists(BuildIds.buildId(late.eventId()))).isFalse();
    }

    @Test
    void anOrgDeletionCancelsAQueueFilledToItsCap() {
        String orgId = fixtures.newOrg();
        int cap = limits.forOrg(orgId).maxQueued();
        List<String> appIds = IntStream.range(0, 5)
                .mapToObj(i -> fixtures.newApp(orgId, "ACTIVE"))
                .toList();
        List<UUID> waiting = IntStream.range(0, cap)
                .mapToObj(i -> fixtures.newBuild(orgId, appIds.get(i % appIds.size())))
                .toList();

        publisher.publish(OrgDeleted.of(orgId, "user-1"));

        await().atMost(WAIT).until(() -> cancelledCount(orgId) == cap);
        assertThat(driver.cancelledEventsOfOrg(orgId))
                .hasSize(cap)
                .extracting(event -> UUID.fromString(text(event, "buildId")))
                .containsExactlyInAnyOrderElementsOf(waiting);
    }

    private UUID queue(GitPushReceived push) {
        publisher.publish(push);
        UUID buildId = BuildIds.buildId(push.eventId());
        awaitStatus(buildId, BuildStatus.QUEUED);
        return buildId;
    }

    private void awaitStatus(UUID buildId, BuildStatus status) {
        await().atMost(WAIT)
                .pollInterval(Duration.ofMillis(50))
                .until(() -> jdbc.queryForList(
                                "SELECT status FROM build_queue.builds WHERE build_id = ?", String.class, buildId)
                        .contains(status.name()));
    }

    private boolean exists(UUID buildId) {
        return !jdbc.queryForList("SELECT 1 FROM build_queue.builds WHERE build_id = ?", Integer.class, buildId)
                .isEmpty();
    }

    private int cancelledCount(String orgId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM build_queue.builds WHERE org_id = ? AND status = 'CANCELLED'"
                        + " AND status_reason = ?",
                Integer.class,
                orgId,
                BuildCancelled.REASON_ORG_DELETED);
    }

    private static AppDeleted appDeleted(String orgId, String appId) {
        return AppDeleted.of(orgId, appId, "app-" + appId.substring(4, 12), "user-1");
    }

    private static String newOrg() {
        return "org-" + UUID.randomUUID();
    }

    private static String newApp() {
        return "app-" + UUID.randomUUID();
    }
}
