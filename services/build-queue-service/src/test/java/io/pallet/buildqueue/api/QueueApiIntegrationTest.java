package io.pallet.buildqueue.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.build.PriorityClass;
import io.pallet.buildqueue.support.ReadModelFixtures;
import io.pallet.buildqueue.support.Tokens;
import io.pallet.common.api.ApiResponse;
import io.pallet.common.error.ErrorResponse;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.assertions.PalletAssertions;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Platform-wide capacity counts every org's builds, so each test starts from an empty table. Builds are written
 * directly, and no dispatcher round runs in tests unless one asks, so the snapshot holds still while it is read.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, Tokens.LocalDecoder.class})
class QueueApiIntegrationTest {

    private static final String QUEUE = "/api/v1/build-queue/orgs/{orgId}/queue";
    private static final int GLOBAL_CONCURRENCY = 4;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    private ReadModelFixtures fixtures;
    private BuildRows rows;

    /** An org at its limit of one: a running build, then three waiting ones held for different reasons. */
    private record AtLimit(String orgId, String token, UUID running, UUID appBusy, UUID interactive, UUID automatic) {}

    @BeforeEach
    void setUp() {
        fixtures = new ReadModelFixtures(jdbc);
        rows = new BuildRows(jdbc);
        rows.quiesce();
    }

    @AfterEach
    void tearDown() {
        rows.quiesce();
        fixtures.cleanUp();
    }

    @Test
    void inFlightBuildsComeFirstThenWaitingOnesInDispatchOrderEachWithItsReason() throws Exception {
        AtLimit org = atLimit();
        String neighbour = fixtures.newOrg();
        String neighbourApp = fixtures.newApp(neighbour, "ACTIVE");
        UUID neighbourRunning = rows.inFlight(neighbour, neighbourApp, BuildStatus.RUNNING, Duration.ofMinutes(3));
        UUID neighbourWaiting = rows.queued(neighbour, neighbourApp, PriorityClass.INTERACTIVE, Duration.ofMinutes(20));

        MockHttpServletResponse response = perform(get(QUEUE, org.orgId()), org.token());
        JsonNode view = data(response);

        JsonNode usage = view.path("usage");
        assertThat(usage.path("inFlight").asInt()).isEqualTo(1);
        assertThat(usage.path("maxConcurrent").asInt()).isEqualTo(1);
        assertThat(usage.path("queued").asLong()).isEqualTo(3);
        assertThat(usage.path("maxQueued").asInt()).isEqualTo(50);
        assertThat(usage.path("profile").asString()).isEqualTo("default");
        assertThat(usage.path("dispatchPaused").asBoolean()).isFalse();

        JsonNode builds = view.path("builds");
        assertThat(builds.path("totalElements").asLong()).isEqualTo(4);
        List<JsonNode> entries = entries(builds);
        assertThat(entries)
                .extracting(entry -> entry.path("buildId").asString())
                .containsExactly(
                        org.running().toString(),
                        org.interactive().toString(),
                        org.appBusy().toString(),
                        org.automatic().toString());
        assertThat(entries.get(0).path("status").asString()).isEqualTo("RUNNING");
        assertThat(entries.get(0).path("position").isNull()).isTrue();
        assertThat(entries.get(0).path("waitingOn").isNull()).isTrue();
        assertWaiting(entries.get(1), 1, "ORG_CONCURRENCY");
        assertWaiting(entries.get(2), 2, "APP_BUSY");
        assertWaiting(entries.get(3), 3, "ORG_CONCURRENCY");

        String body = response.getContentAsString();
        assertThat(body)
                .doesNotContain(neighbour)
                .doesNotContain(neighbourApp)
                .doesNotContain(neighbourRunning.toString())
                .doesNotContain(neighbourWaiting.toString());
    }

    @Test
    void aBuildItsOrgHasRoomForWaitsOnAFullPlatformWithoutSeeingWhoFillsIt() throws Exception {
        String orgId = fixtures.newOrg();
        String token = viewer(orgId);
        UUID waiting = rows.queued(orgId, fixtures.newApp(orgId, "ACTIVE"), PriorityClass.AUTOMATIC, Duration.ZERO);
        List<UUID> elsewhere = new ArrayList<>();
        for (int i = 0; i < GLOBAL_CONCURRENCY; i++) {
            String other = fixtures.newOrg();
            elsewhere.add(rows.inFlight(other, fixtures.newApp(other, "ACTIVE"), BuildStatus.RUNNING, Duration.ZERO));
        }

        MockHttpServletResponse response = perform(get(QUEUE, orgId), token);
        JsonNode view = data(response);

        assertThat(view.path("usage").propertyNames())
                .containsExactlyInAnyOrder(
                        "inFlight", "maxConcurrent", "queued", "maxQueued", "profile", "dispatchPaused");
        assertThat(view.path("usage").path("inFlight").asInt()).isZero();
        assertThat(entries(view.path("builds"))).singleElement().satisfies(entry -> {
            assertThat(entry.path("buildId").asString()).isEqualTo(waiting.toString());
            assertWaiting(entry, 1, "PLATFORM_CAPACITY");
        });
        String body = response.getContentAsString();
        elsewhere.forEach(id -> assertThat(body).doesNotContain(id.toString()));

        rows.succeed(elsewhere.getFirst());

        assertWaiting(entries(queue(orgId, token).path("builds")).getFirst(), 1, "NEXT_ROUND");
    }

    @Test
    void positionsContinueAcrossPages() throws Exception {
        AtLimit org = atLimit();

        List<UUID> oneByOne = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            JsonNode builds = queue(org.orgId(), org.token(), "page", String.valueOf(page), "size", "1")
                    .path("builds");
            assertThat(builds.path("totalElements").asLong()).isEqualTo(4);
            assertThat(builds.path("totalPages").asInt()).isEqualTo(4);
            JsonNode entry = entries(builds).getFirst();
            oneByOne.add(UUID.fromString(entry.path("buildId").asString()));
            positions.add(
                    entry.path("position").isNull()
                            ? null
                            : entry.path("position").asInt());
        }
        assertThat(oneByOne).containsExactly(org.running(), org.interactive(), org.appBusy(), org.automatic());
        assertThat(positions).containsExactly(null, 1, 2, 3);

        JsonNode second =
                queue(org.orgId(), org.token(), "page", "1", "size", "2").path("builds");
        assertThat(entries(second))
                .extracting(entry -> entry.path("position").asInt())
                .containsExactly(2, 3);
        assertThat(second.path("last").asBoolean()).isTrue();

        assertThat(entries(queue(org.orgId(), org.token(), "page", "9", "size", "2")
                        .path("builds")))
                .isEmpty();
    }

    @Test
    void runningBuildsComeBeforeDispatchedOnesEachOldestDispatchFirst() throws Exception {
        String orgId = fixtures.newOrg();
        String token = viewer(orgId);
        UUID recentRunning =
                rows.inFlight(orgId, fixtures.newApp(orgId, "ACTIVE"), BuildStatus.RUNNING, Duration.ofMinutes(1));
        UUID oldDispatched =
                rows.inFlight(orgId, fixtures.newApp(orgId, "ACTIVE"), BuildStatus.DISPATCHED, Duration.ofMinutes(5));
        UUID oldRunning =
                rows.inFlight(orgId, fixtures.newApp(orgId, "ACTIVE"), BuildStatus.RUNNING, Duration.ofMinutes(10));

        JsonNode view = queue(orgId, token);

        assertThat(entries(view.path("builds")))
                .extracting(entry -> entry.path("buildId").asString())
                .containsExactly(oldRunning.toString(), recentRunning.toString(), oldDispatched.toString());
        assertThat(view.path("usage").path("inFlight").asInt()).isEqualTo(3);
        assertThat(view.path("usage").path("queued").asLong()).isZero();
    }

    @Test
    void aMemberOfAnotherOrgCannotReadTheQueue() throws Exception {
        AtLimit org = atLimit();
        String outsider = viewer(fixtures.newOrg());

        MockHttpServletResponse response = perform(get(QUEUE, org.orgId()), outsider);

        assertThat(response.getStatus()).isEqualTo(404);
        PalletAssertions.assertThat(jsonMapper.readValue(response.getContentAsString(), ErrorResponse.class))
                .isFailure()
                .hasErrorCode("ORG_NOT_FOUND");
    }

    @Nested
    @TestPropertySource(properties = "pallet.build-queue.dispatch.paused=true")
    class WhileDispatchIsPaused {

        @Autowired
        private MockMvc pausedMvc;

        @Test
        void everyWaitingBuildIsHeldByThePause() throws Exception {
            AtLimit org = atLimit();

            MockHttpServletResponse response = pausedMvc
                    .perform(get(QUEUE, org.orgId()).header("Authorization", "Bearer " + org.token()))
                    .andReturn()
                    .getResponse();
            JsonNode view = data(response);

            assertThat(view.path("usage").path("dispatchPaused").asBoolean()).isTrue();
            List<JsonNode> waiting = entries(view.path("builds")).subList(1, 4);
            assertThat(waiting)
                    .extracting(entry -> entry.path("waitingOn").asString())
                    .containsOnly("DISPATCH_PAUSED");
            assertThat(waiting)
                    .extracting(entry -> entry.path("position").asInt())
                    .containsExactly(1, 2, 3);
        }
    }

    private AtLimit atLimit() {
        String orgId = fixtures.newOrg();
        String busyApp = fixtures.newApp(orgId, "ACTIVE");
        UUID running = rows.inFlight(orgId, busyApp, BuildStatus.RUNNING, Duration.ofMinutes(2));
        UUID appBusy = rows.queued(orgId, busyApp, PriorityClass.AUTOMATIC, Duration.ofMinutes(10));
        UUID interactive =
                rows.queued(orgId, fixtures.newApp(orgId, "ACTIVE"), PriorityClass.INTERACTIVE, Duration.ofMinutes(1));
        UUID automatic =
                rows.queued(orgId, fixtures.newApp(orgId, "ACTIVE"), PriorityClass.AUTOMATIC, Duration.ofMinutes(5));
        return new AtLimit(orgId, viewer(orgId), running, appBusy, interactive, automatic);
    }

    private String viewer(String orgId) {
        return Tokens.forUser(fixtures.newMember(orgId, "viewer", "ACTIVE")).signed();
    }

    private JsonNode queue(String orgId, String token, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get(QUEUE, orgId);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return data(perform(request, token));
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token))
                .andReturn()
                .getResponse();
    }

    private JsonNode data(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(200);
        ApiResponse<JsonNode> body =
                jsonMapper.readValue(response.getContentAsString(), new TypeReference<ApiResponse<JsonNode>>() {});
        PalletAssertions.assertThat(body).isSuccess();
        return body.data();
    }

    private static List<JsonNode> entries(JsonNode page) {
        return StreamSupport.stream(page.path("content").spliterator(), false).toList();
    }

    private static void assertWaiting(JsonNode entry, int position, String waitingOn) {
        assertThat(entry.path("status").asString()).isEqualTo("QUEUED");
        assertThat(entry.path("position").asInt()).isEqualTo(position);
        assertThat(entry.path("waitingOn").asString()).isEqualTo(waitingOn);
    }
}
