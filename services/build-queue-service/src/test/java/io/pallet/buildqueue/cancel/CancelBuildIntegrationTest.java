package io.pallet.buildqueue.cancel;

import static io.pallet.buildqueue.cancel.CancelDriver.text;
import static io.pallet.buildqueue.support.PushEvents.push;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.pallet.buildqueue.build.BuildEvents;
import io.pallet.buildqueue.build.BuildIds;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.dispatch.Dispatcher;
import io.pallet.buildqueue.support.ReadModelFixtures;
import io.pallet.buildqueue.support.Tokens;
import io.pallet.common.api.ApiResponse;
import io.pallet.common.error.ErrorResponse;
import io.pallet.common.events.BuildCancelled;
import io.pallet.common.events.BuildQueued;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.messaging.PlatformEventPublisher;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.assertions.ErrorResponseAssert;
import io.pallet.common.test.assertions.PalletAssertions;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, Tokens.LocalDecoder.class})
class CancelBuildIntegrationTest {

    private static final String CANCEL = "/api/v1/build-queue/orgs/{orgId}/builds/{buildId}/cancel";
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final int RACE_ITERATIONS = 100;
    private static final Duration RACE_MAX_HEAD_START = Duration.ofMillis(40);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private Dispatcher dispatcher;

    @Autowired
    private PlatformEventPublisher publisher;

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
        driver.quiesce();
        fixtures.cleanUp();
    }

    @Test
    void aDeveloperCancelsAWaitingBuildOnceAndAgainGetsItUnchanged() throws Exception {
        String orgId = fixtures.newOrg();
        String userId = fixtures.newMember(orgId, "developer", "ACTIVE");
        String appId = fixtures.newApp(orgId, "ACTIVE");
        UUID buildId = fixtures.newBuild(orgId, appId);
        String token = Tokens.forUser(userId).signed();

        JsonNode first = cancelled(cancel(orgId, buildId, token));

        assertThat(text(first, "buildId")).isEqualTo(buildId.toString());
        assertThat(text(first, "appId")).isEqualTo(appId);
        assertThat(text(first, "status")).isEqualTo("CANCELLED");
        assertThat(text(first, "statusReason")).isEqualTo(BuildCancelled.REASON_USER_CANCELLED);
        assertThat(text(first, "finishedAt")).isNotNull();
        assertThat(first.has("installationId")).isFalse();
        assertThat(first.has("repoId")).isFalse();
        assertThat(first.has("traceparent")).isFalse();
        assertThat(first.has("cancelledBy")).isFalse();
        assertThat(driver.cancelledBy(buildId)).isEqualTo(userId);

        List<JsonNode> cancellations = driver.cancelledEvents(buildId);
        assertThat(cancellations).singleElement().satisfies(event -> {
            assertThat(text(event, "eventId"))
                    .isEqualTo(BuildIds.cancelledEventId(buildId).toString());
            assertThat(text(event, "orgId")).isEqualTo(orgId);
            assertThat(text(event, "appId")).isEqualTo(appId);
            assertThat(text(event, "reason")).isEqualTo(BuildCancelled.REASON_USER_CANCELLED);
            assertThat(text(event, "supersededBy")).isNull();
        });
        assertThat(driver.auditEvents(buildId)).singleElement().satisfies(event -> {
            assertThat(text(event, "orgId")).isEqualTo(orgId);
            assertThat(text(event, "actor")).isEqualTo(userId);
            assertThat(text(event, "action")).isEqualTo(BuildEvents.AUDIT_BUILD_CANCELLED);
            assertThat(text(event.path("context"), "appId")).isEqualTo(appId);
        });

        JsonNode again = cancelled(cancel(orgId, buildId, token));

        assertThat(again).isEqualTo(first);
        assertThat(driver.cancelledEvents(buildId)).hasSize(1);
        assertThat(driver.auditEvents(buildId)).hasSize(1);
    }

    @Test
    void aViewerCannotCancel() throws Exception {
        String orgId = fixtures.newOrg();
        String viewer = fixtures.newMember(orgId, "viewer", "ACTIVE");
        UUID buildId = fixtures.newBuild(orgId, fixtures.newApp(orgId, "ACTIVE"));

        assertError(cancel(orgId, buildId, Tokens.forUser(viewer).signed()), 403)
                .hasErrorCode("INSUFFICIENT_ROLE");

        assertThat(driver.status(buildId)).isEqualTo(BuildStatus.QUEUED);
        assertThat(driver.cancelledEvents(buildId)).isEmpty();
    }

    @Test
    void anotherOrgsBuildIsNotFound() throws Exception {
        String orgId = fixtures.newOrg();
        String token =
                Tokens.forUser(fixtures.newMember(orgId, "owner", "ACTIVE")).signed();
        String otherOrg = fixtures.newOrg();
        UUID othersBuild = fixtures.newBuild(otherOrg, fixtures.newApp(otherOrg, "ACTIVE"));

        assertError(cancel(orgId, othersBuild, token), 404).hasErrorCode("BUILD_NOT_FOUND");
        assertError(cancel(orgId, UUID.randomUUID(), token), 404).hasErrorCode("BUILD_NOT_FOUND");

        assertThat(driver.status(othersBuild)).isEqualTo(BuildStatus.QUEUED);
        assertThat(driver.cancelledEvents(othersBuild)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(
            nullValues = "null",
            value = {"DISPATCHED, null", "SUCCEEDED, null", "SUPERSEDED, SUPERSEDED", "CANCELLED, APP_DELETED"})
    void aBuildThatLeftTheQueueAnyOtherWayIsNotCancellable(BuildStatus status, String reason) throws Exception {
        String orgId = fixtures.newOrg();
        String token =
                Tokens.forUser(fixtures.newMember(orgId, "developer", "ACTIVE")).signed();
        UUID buildId = fixtures.newBuild(orgId, fixtures.newApp(orgId, "ACTIVE"));
        driver.moveTo(buildId, status, reason);

        MockHttpServletResponse response = cancel(orgId, buildId, token);

        assertError(response, 409).hasErrorCode("BUILD_NOT_CANCELLABLE");
        assertThat(error(response).meta()).isEqualTo(Map.of("status", status.name()));
        assertThat(driver.status(buildId)).isEqualTo(status);
        assertThat(driver.statusReason(buildId)).isEqualTo(reason);
        assertThat(driver.cancelledEvents(buildId)).isEmpty();
        assertThat(driver.auditEvents(buildId)).isEmpty();
    }

    @Test
    void cancelAndDispatchNeverBothWin() throws Exception {
        Map<BuildStatus, Integer> outcomes = new EnumMap<>(BuildStatus.class);
        ExecutorService racers = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < RACE_ITERATIONS; i++) {
                outcomes.merge(race(racers), 1, Integer::sum);
            }
        } finally {
            racers.shutdownNow();
        }

        assertThat(outcomes)
                .as("a race that one side always wins proves nothing")
                .containsOnlyKeys(BuildStatus.CANCELLED, BuildStatus.DISPATCHED);
    }

    @Test
    void theAppsNextPushIsQueuedNormallyAfterACancel() throws Exception {
        String orgId = fixtures.newOrg();
        String token =
                Tokens.forUser(fixtures.newMember(orgId, "developer", "ACTIVE")).signed();
        String appId = "app-" + UUID.randomUUID();
        GitPushReceived first = push(orgId, appId).build();
        UUID firstBuild = BuildIds.buildId(first.eventId());
        publisher.publish(first);
        awaitStatus(firstBuild, BuildStatus.QUEUED);
        cancelled(cancel(orgId, firstBuild, token));

        GitPushReceived next = push(orgId, appId).build();
        UUID nextBuild = BuildIds.buildId(next.eventId());
        publisher.publish(next);

        awaitStatus(nextBuild, BuildStatus.QUEUED);
        assertThat(driver.status(firstBuild)).isEqualTo(BuildStatus.CANCELLED);
        assertThat(queuedEvent(orgId, nextBuild).path("supersedes").isNull()).isTrue();
    }

    /**
     * One queued build in a fresh org, a cancel and a dispatcher round released together. The round starts after a
     * random head start for the request, so either side can get to the org lock first.
     *
     * @return the status the build ended in
     */
    private BuildStatus race(ExecutorService racers) throws Exception {
        String orgId = fixtures.newOrg();
        String token =
                Tokens.forUser(fixtures.newMember(orgId, "developer", "ACTIVE")).signed();
        UUID buildId = fixtures.newBuild(orgId, fixtures.newApp(orgId, "ACTIVE"));
        CountDownLatch go = new CountDownLatch(1);

        Future<MockHttpServletResponse> cancel = racers.submit(() -> {
            go.await();
            return cancel(orgId, buildId, token);
        });
        long headStartMillis = ThreadLocalRandom.current().nextLong(RACE_MAX_HEAD_START.toMillis() + 1);
        Future<Dispatcher.Round> round = racers.submit(() -> {
            go.await();
            Thread.sleep(headStartMillis);
            return dispatcher.runRound();
        });
        go.countDown();
        MockHttpServletResponse response = cancel.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        round.get(WAIT.toSeconds(), TimeUnit.SECONDS);

        BuildStatus status = driver.status(buildId);
        if (status == BuildStatus.CANCELLED) {
            assertThat(response.getStatus()).as("cancel of a cancelled build").isEqualTo(200);
            assertThat(driver.cancelledEvents(buildId)).hasSize(1);
            assertThat(driver.dispatchedEvents(buildId)).isEmpty();
        } else {
            assertThat(status).isEqualTo(BuildStatus.DISPATCHED);
            assertError(response, 409).hasErrorCode("BUILD_NOT_CANCELLABLE");
            assertThat(error(response).meta()).isEqualTo(Map.of("status", BuildStatus.DISPATCHED.name()));
            assertThat(driver.dispatchedEvents(buildId)).hasSize(1);
            assertThat(driver.cancelledEvents(buildId)).isEmpty();
            driver.finish(buildId);
        }
        return status;
    }

    private MockHttpServletResponse cancel(String orgId, UUID buildId, String token) throws Exception {
        return mvc.perform(post(CANCEL, orgId, buildId).header("Authorization", "Bearer " + token))
                .andReturn()
                .getResponse();
    }

    /** The response's build, after checking it is a success. */
    private JsonNode cancelled(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(200);
        ApiResponse<JsonNode> body =
                jsonMapper.readValue(response.getContentAsString(), new TypeReference<ApiResponse<JsonNode>>() {});
        PalletAssertions.assertThat(body).isSuccess();
        return body.data();
    }

    private ErrorResponseAssert assertError(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        return PalletAssertions.assertThat(error(response)).isFailure().hasStatusCode(status);
    }

    private ErrorResponse error(MockHttpServletResponse response) throws Exception {
        return jsonMapper.readValue(response.getContentAsString(), ErrorResponse.class);
    }

    private void awaitStatus(UUID buildId, BuildStatus status) {
        await().atMost(WAIT)
                .pollInterval(Duration.ofMillis(50))
                .until(() -> jdbc.queryForList(
                                "SELECT status FROM build_queue.builds WHERE build_id = ?", String.class, buildId)
                        .contains(status.name()));
    }

    private JsonNode queuedEvent(String orgId, UUID buildId) {
        return jdbc
                .queryForList(
                        "SELECT payload::text FROM build_queue.outbox_events"
                                + " WHERE org_id = ? AND event_type = ? AND payload->>'buildId' = ?",
                        String.class,
                        orgId,
                        BuildQueued.TYPE,
                        buildId.toString())
                .stream()
                .map(jsonMapper::readTree)
                .findFirst()
                .orElseThrow();
    }
}
