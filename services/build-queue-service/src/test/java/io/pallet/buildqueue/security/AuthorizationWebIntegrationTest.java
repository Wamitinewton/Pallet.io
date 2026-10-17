package io.pallet.buildqueue.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.buildqueue.support.ProbeController;
import io.pallet.buildqueue.support.ReadModelFixtures;
import io.pallet.buildqueue.support.Tokens;
import io.pallet.common.api.ApiResponse;
import io.pallet.common.error.ErrorResponse;
import io.pallet.common.security.RedisRevokedSessionRegistry;
import io.pallet.common.security.RevokedSessionRegistry;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.assertions.ErrorResponseAssert;
import io.pallet.common.test.assertions.PalletAssertions;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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
import tools.jackson.databind.node.ObjectNode;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, Tokens.LocalDecoder.class, ProbeController.class})
class AuthorizationWebIntegrationTest {

    private static final String PREFIX = "/api/v1";
    private static final String ORG = PREFIX + ProbeController.ORG;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RevokedSessionRegistry revokedSessions;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JsonMapper jsonMapper;

    private ReadModelFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ReadModelFixtures(jdbc);
    }

    @AfterEach
    void tearDown() {
        fixtures.cleanUp();
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        assertError(mvc.perform(get(ORG + "/viewer", "org-1")).andReturn().getResponse(), 401);
    }

    @Test
    void theApiDocsAreServedWithoutAToken() throws Exception {
        MockHttpServletResponse response = mvc.perform(get(PREFIX + "/build-queue/v3/api-docs"))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void aRevokedSessionIsUnauthorizedThroughTheRedisRegistry() throws Exception {
        assertThat(revokedSessions).isInstanceOf(RedisRevokedSessionRegistry.class);
        String orgId = fixtures.newOrg();
        Tokens token = Tokens.forUser(fixtures.newMember(orgId, "owner", "ACTIVE"));
        String signed = token.signed();

        assertThat(call("/viewer", orgId, signed).getStatus()).isEqualTo(200);
        revokedSessions.revoke(token.sessionId(), Duration.ofMinutes(5));

        assertError(call("/viewer", orgId, signed), 401);
    }

    @ParameterizedTest
    @CsvSource({
        "viewer, viewer, 200",
        "viewer, developer, 403",
        "developer, viewer, 200",
        "developer, developer, 200",
        "admin, developer, 200",
        "owner, developer, 200"
    })
    void eachRoleAgainstEachFloor(String role, String floor, int expected) throws Exception {
        String orgId = fixtures.newOrg();
        String userId = fixtures.newMember(orgId, role, "ACTIVE");

        MockHttpServletResponse response =
                call("/" + floor, orgId, Tokens.forUser(userId).signed());

        if (expected == 200) {
            assertThat(data(response, new TypeReference<ApiResponse<String>>() {}))
                    .isEqualTo(role.toUpperCase(Locale.ROOT));
        } else {
            assertError(response, 403).hasErrorCode("INSUFFICIENT_ROLE");
        }
    }

    @Test
    void noMembershipIsOrgNotFound() throws Exception {
        String orgId = fixtures.newOrg();
        fixtures.newMember(orgId, "owner", "ACTIVE");
        String stranger = Tokens.forUser("user-" + UUID.randomUUID()).signed();

        assertError(call("/viewer", orgId, stranger), 404).hasErrorCode("ORG_NOT_FOUND");
    }

    @Test
    void aDeletedOrgGetsTheSameBodyAsNoMembership() throws Exception {
        String deletedOrg = fixtures.newOrg();
        String userId = fixtures.newMember(deletedOrg, "owner", "ACTIVE");
        fixtures.deleteOrg(deletedOrg);
        String otherOrg = fixtures.newOrg();
        fixtures.newMember(otherOrg, "owner", "ACTIVE");
        String token = Tokens.forUser(userId).signed();

        assertThat(normalizedError(call("/viewer", deletedOrg, token), 404, "ORG_NOT_FOUND", deletedOrg))
                .isEqualTo(normalizedError(call("/viewer", otherOrg, token), 404, "ORG_NOT_FOUND", otherOrg));
    }

    @Test
    void aRemovedMemberIsNotAMember() throws Exception {
        String orgId = fixtures.newOrg();
        String userId = fixtures.newMember(orgId, "owner", "REMOVED");

        assertError(call("/viewer", orgId, Tokens.forUser(userId).signed()), 403)
                .hasErrorCode("NOT_A_MEMBER");
    }

    @Test
    void aRemovalTakesEffectOnTheNextRequestWithTheSameToken() throws Exception {
        String orgId = fixtures.newOrg();
        String userId = fixtures.newMember(orgId, "developer", "ACTIVE");
        String token = Tokens.forUser(userId).signed();

        assertThat(call("/viewer", orgId, token).getStatus()).isEqualTo(200);
        fixtures.setStatus(orgId, userId, "REMOVED");

        assertError(call("/viewer", orgId, token), 403).hasErrorCode("NOT_A_MEMBER");
    }

    @Test
    void forgedOrgAndRoleClaimsAreIgnored() throws Exception {
        String orgId = fixtures.newOrg();
        fixtures.newMember(orgId, "owner", "ACTIVE");
        String forged = Tokens.forUser("user-" + UUID.randomUUID())
                .claimingOrg(orgId)
                .claimingRoles("owner", "admin")
                .signed();

        assertError(call("/developer", orgId, forged), 404).hasErrorCode("ORG_NOT_FOUND");
    }

    @Test
    void aClaimedOwnerRoleDoesNotLiftAViewer() throws Exception {
        String orgId = fixtures.newOrg();
        String userId = fixtures.newMember(orgId, "viewer", "ACTIVE");
        String token =
                Tokens.forUser(userId).claimingOrg(orgId).claimingRoles("owner").signed();

        assertError(call("/developer", orgId, token), 403).hasErrorCode("INSUFFICIENT_ROLE");
    }

    @Test
    void activeAndDeletedAppsOfThisOrgAreInScope() throws Exception {
        String orgId = fixtures.newOrg();
        String token =
                Tokens.forUser(fixtures.newMember(orgId, "viewer", "ACTIVE")).signed();

        for (String status : List.of("ACTIVE", "DELETED")) {
            String appId = fixtures.newApp(orgId, status);
            assertThat(data(call("/apps/" + appId, orgId, token), new TypeReference<ApiResponse<String>>() {}))
                    .as(status)
                    .isEqualTo(appId);
        }
    }

    @Test
    void anotherOrgsAppAndAnUnknownAppAreTheSameNotFound() throws Exception {
        String orgId = fixtures.newOrg();
        String otherOrg = fixtures.newOrg();
        String token =
                Tokens.forUser(fixtures.newMember(orgId, "owner", "ACTIVE")).signed();
        String othersApp = fixtures.newApp(otherOrg, "ACTIVE");
        String unknownApp = "app-" + UUID.randomUUID();

        assertThat(normalizedError(call("/apps/" + othersApp, orgId, token), 404, "APP_NOT_FOUND", othersApp))
                .isEqualTo(
                        normalizedError(call("/apps/" + unknownApp, orgId, token), 404, "APP_NOT_FOUND", unknownApp));
    }

    @Test
    void aBuildOfThisOrgIsInScope() throws Exception {
        String orgId = fixtures.newOrg();
        String token =
                Tokens.forUser(fixtures.newMember(orgId, "viewer", "ACTIVE")).signed();
        UUID buildId = fixtures.newBuild(orgId, fixtures.newApp(orgId, "ACTIVE"));

        assertThat(data(call("/builds/" + buildId, orgId, token), new TypeReference<ApiResponse<UUID>>() {}))
                .isEqualTo(buildId);
    }

    @Test
    void anotherOrgsBuildAndAnUnknownBuildAreTheSameNotFound() throws Exception {
        String orgId = fixtures.newOrg();
        String otherOrg = fixtures.newOrg();
        String token =
                Tokens.forUser(fixtures.newMember(orgId, "owner", "ACTIVE")).signed();
        UUID othersBuild = fixtures.newBuild(otherOrg, fixtures.newApp(otherOrg, "ACTIVE"));
        UUID unknownBuild = UUID.randomUUID();

        assertThat(normalizedError(call("/builds/" + othersBuild, orgId, token), 404, "BUILD_NOT_FOUND", othersBuild))
                .isEqualTo(normalizedError(
                        call("/builds/" + unknownBuild, orgId, token), 404, "BUILD_NOT_FOUND", unknownBuild));
    }

    @Test
    void denialsAreCountedByReason() throws Exception {
        String orgId = fixtures.newOrg();
        String viewer = fixtures.newMember(orgId, "viewer", "ACTIVE");
        String removed = fixtures.newMember(orgId, "viewer", "REMOVED");
        String deletedOrg = fixtures.newOrg();
        String deletedOrgMember = fixtures.newMember(deletedOrg, "owner", "ACTIVE");
        fixtures.deleteOrg(deletedOrg);
        List<String> reasons = List.of(
                AccessResolver.REASON_NO_MEMBERSHIP,
                AccessResolver.REASON_ORG_DELETED,
                AccessResolver.REASON_NOT_A_MEMBER,
                AccessResolver.REASON_INSUFFICIENT_ROLE);
        List<Double> before = reasons.stream().map(this::denied).toList();

        call("/viewer", "org-" + UUID.randomUUID(), Tokens.forUser(viewer).signed());
        call("/viewer", deletedOrg, Tokens.forUser(deletedOrgMember).signed());
        call("/viewer", orgId, Tokens.forUser(removed).signed());
        call("/developer", orgId, Tokens.forUser(viewer).signed());

        for (int i = 0; i < reasons.size(); i++) {
            assertThat(denied(reasons.get(i))).as(reasons.get(i)).isEqualTo(before.get(i) + 1);
        }
    }

    private MockHttpServletResponse call(String path, String orgId, String token) throws Exception {
        return mvc.perform(get(ORG + path, orgId).header("Authorization", "Bearer " + token))
                .andReturn()
                .getResponse();
    }

    private <T> T data(MockHttpServletResponse response, TypeReference<ApiResponse<T>> type) throws Exception {
        assertThat(response.getStatus()).isEqualTo(200);
        ApiResponse<T> body = jsonMapper.readValue(response.getContentAsString(), type);
        PalletAssertions.assertThat(body).isSuccess();
        return body.data();
    }

    private ErrorResponseAssert assertError(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        ErrorResponse body = jsonMapper.readValue(response.getContentAsString(), ErrorResponse.class);
        return PalletAssertions.assertThat(body).isFailure().hasStatusCode(status);
    }

    /** The body with its timestamp dropped and the probed id in its path replaced, so two responses can be compared. */
    private JsonNode normalizedError(MockHttpServletResponse response, int status, String code, Object pathId)
            throws Exception {
        assertError(response, status).hasErrorCode(code);
        ObjectNode json = (ObjectNode) jsonMapper.readTree(response.getContentAsString());
        assertThat(json.has("timestamp")).isTrue();
        json.remove("timestamp");
        if (json.has("path")) {
            json.put("path", json.get("path").asString().replace(String.valueOf(pathId), "{id}"));
        }
        return json;
    }

    private double denied(String reason) {
        Counter counter = meters.find(AccessResolver.AUTHZ_DENIED)
                .tag(AccessResolver.TAG_REASON, reason)
                .counter();
        return counter == null ? 0 : counter.count();
    }
}
