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
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, Tokens.LocalDecoder.class})
class BuildHistoryIntegrationTest {

    private static final String HISTORY = "/api/v1/build-queue/orgs/{orgId}/apps/{appId}/builds";
    private static final String BUILD = "/api/v1/build-queue/orgs/{orgId}/builds/{buildId}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    private ReadModelFixtures fixtures;
    private BuildRows rows;
    private String orgId;
    private String token;

    @BeforeEach
    void setUp() {
        fixtures = new ReadModelFixtures(jdbc);
        rows = new BuildRows(jdbc);
        orgId = fixtures.newOrg();
        token = Tokens.forUser(fixtures.newMember(orgId, "viewer", "ACTIVE")).signed();
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanUp();
    }

    @Test
    void anAppsBuildsInEveryStatusComeNewestFirst() throws Exception {
        String appId = fixtures.newApp(orgId, "ACTIVE");
        List<UUID> newestFirst = List.of(
                rows.queued(orgId, appId, PriorityClass.AUTOMATIC, Duration.ofMinutes(1)),
                rows.finished(orgId, appId, BuildStatus.SUPERSEDED, Duration.ofMinutes(2)),
                rows.inFlight(orgId, appId, BuildStatus.RUNNING, Duration.ofMinutes(3)),
                rows.finished(orgId, appId, BuildStatus.CANCELLED, Duration.ofMinutes(5)),
                rows.finished(orgId, appId, BuildStatus.SUPERSEDED, Duration.ofMinutes(6)),
                rows.finished(orgId, appId, BuildStatus.FAILED, Duration.ofMinutes(7)),
                rows.finished(orgId, appId, BuildStatus.EXPIRED, Duration.ofMinutes(8)),
                rows.finished(orgId, appId, BuildStatus.SUCCEEDED, Duration.ofMinutes(9)));
        rows.finished(orgId, fixtures.newApp(orgId, "ACTIVE"), BuildStatus.SUCCEEDED, Duration.ZERO);

        JsonNode page = page(perform(get(HISTORY, orgId, appId)));

        assertThat(ids(page)).containsExactlyElementsOf(newestFirst);
        assertThat(page.path("totalElements").asLong()).isEqualTo(newestFirst.size());
        assertThat(page.path("content").get(1).path("statusReason").asString()).isEqualTo("SUPERSEDED");
        assertThat(page.path("content").get(0).has("installationId")).isFalse();

        JsonNode second =
                page(perform(get(HISTORY, orgId, appId).param("page", "1").param("size", "3")));
        assertThat(ids(second)).containsExactlyElementsOf(newestFirst.subList(3, 6));
        assertThat(second.path("totalPages").asInt()).isEqualTo(3);
    }

    @Test
    void theStatusFilterReturnsOnlyThoseStatuses() throws Exception {
        String appId = fixtures.newApp(orgId, "ACTIVE");
        UUID newer = rows.finished(orgId, appId, BuildStatus.SUPERSEDED, Duration.ofMinutes(1));
        rows.finished(orgId, appId, BuildStatus.SUCCEEDED, Duration.ofMinutes(2));
        UUID older = rows.finished(orgId, appId, BuildStatus.SUPERSEDED, Duration.ofMinutes(3));
        UUID failed = rows.finished(orgId, appId, BuildStatus.FAILED, Duration.ofMinutes(4));
        rows.queued(orgId, appId, PriorityClass.AUTOMATIC, Duration.ZERO);

        assertThat(ids(page(perform(get(HISTORY, orgId, appId).param("status", "SUPERSEDED")))))
                .containsExactly(newer, older);
        assertThat(ids(page(perform(get(HISTORY, orgId, appId).param("status", "SUPERSEDED", "FAILED")))))
                .containsExactly(newer, older, failed);

        MockHttpServletResponse unknown = perform(get(HISTORY, orgId, appId).param("status", "DONE"));
        assertThat(unknown.getStatus()).isEqualTo(400);
    }

    @Test
    void anotherOrgsAppIsNotFound() throws Exception {
        String otherOrg = fixtures.newOrg();
        String othersApp = fixtures.newApp(otherOrg, "ACTIVE");
        rows.finished(otherOrg, othersApp, BuildStatus.SUCCEEDED, Duration.ZERO);

        assertNotFound(perform(get(HISTORY, orgId, othersApp)), "APP_NOT_FOUND");
        assertNotFound(perform(get(HISTORY, orgId, "app-" + UUID.randomUUID())), "APP_NOT_FOUND");
    }

    @Test
    void aDeletedAppsHistoryIsStillReturned() throws Exception {
        String appId = fixtures.newApp(orgId, "ACTIVE");
        UUID cancelled = rows.finished(orgId, appId, BuildStatus.CANCELLED, Duration.ofMinutes(1));
        UUID succeeded = rows.finished(orgId, appId, BuildStatus.SUCCEEDED, Duration.ofMinutes(2));
        jdbc.update("UPDATE build_queue.apps SET status = 'DELETED', deleted_at = now() WHERE app_id = ?", appId);

        assertThat(ids(page(perform(get(HISTORY, orgId, appId))))).containsExactly(cancelled, succeeded);
    }

    @Test
    void oneBuildIsReadWithinItsOrgOnly() throws Exception {
        String appId = fixtures.newApp(orgId, "ACTIVE");
        UUID superseded = rows.finished(orgId, appId, BuildStatus.SUPERSEDED, Duration.ofMinutes(1));
        String otherOrg = fixtures.newOrg();
        UUID othersBuild =
                rows.finished(otherOrg, fixtures.newApp(otherOrg, "ACTIVE"), BuildStatus.FAILED, Duration.ZERO);

        MockHttpServletResponse response = perform(get(BUILD, orgId, superseded));
        assertThat(response.getStatus()).isEqualTo(200);
        ApiResponse<JsonNode> body =
                jsonMapper.readValue(response.getContentAsString(), new TypeReference<ApiResponse<JsonNode>>() {});
        PalletAssertions.assertThat(body).isSuccess();
        assertThat(body.data().path("buildId").asString()).isEqualTo(superseded.toString());
        assertThat(body.data().path("status").asString()).isEqualTo("SUPERSEDED");
        assertThat(body.data().path("statusReason").asString()).isEqualTo("SUPERSEDED");
        assertThat(body.data().has("traceparent")).isFalse();

        assertNotFound(perform(get(BUILD, orgId, othersBuild)), "BUILD_NOT_FOUND");
        assertNotFound(perform(get(BUILD, orgId, UUID.randomUUID())), "BUILD_NOT_FOUND");
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token))
                .andReturn()
                .getResponse();
    }

    private JsonNode page(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(200);
        ApiResponse<JsonNode> body =
                jsonMapper.readValue(response.getContentAsString(), new TypeReference<ApiResponse<JsonNode>>() {});
        PalletAssertions.assertThat(body).isSuccess();
        return body.data();
    }

    private void assertNotFound(MockHttpServletResponse response, String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(404);
        PalletAssertions.assertThat(jsonMapper.readValue(response.getContentAsString(), ErrorResponse.class))
                .isFailure()
                .hasErrorCode(code);
    }

    private static List<UUID> ids(JsonNode page) {
        return StreamSupport.stream(page.path("content").spliterator(), false)
                .map(entry -> UUID.fromString(entry.path("buildId").asString()))
                .toList();
    }
}
