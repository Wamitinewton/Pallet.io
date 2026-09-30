package io.pallet.gitintegration.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.api.ApiResponse;
import io.pallet.common.error.ErrorResponse;
import io.pallet.common.security.RedisRevokedSessionRegistry;
import io.pallet.common.security.RevokedSessionRegistry;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.assertions.ErrorResponseAssert;
import io.pallet.common.test.assertions.PalletAssertions;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.observability.MetricsCatalog;
import io.pallet.gitintegration.support.ProbeController;
import io.pallet.gitintegration.support.ReadModelFixtures;
import io.pallet.gitintegration.support.Tokens;
import io.pallet.gitintegration.support.WebhookFixtures;
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
    void aRevokedSessionIsUnauthorizedThroughTheRedisRegistry() throws Exception {
        assertThat(revokedSessions).isInstanceOf(RedisRevokedSessionRegistry.class);
        String orgId = fixtures.newOrg();
        Tokens token = Tokens.forUser(fixtures.newMember(orgId, "owner", "ACTIVE"));
        String signed = token.signed();

        assertThat(call("/viewer", orgId, signed).getStatus()).isEqualTo(200);
        revokedSessions.revoke(token.sessionId(), Duration.ofMinutes(5));

        assertError(call("/viewer", orgId, signed), 401);
    }

    @Test
    void anActiveMemberAtTheFloorIsServedTheirReadModelRole() throws Exception {
        String orgId = fixtures.newOrg();
        String userId = fixtures.newMember(orgId, "developer", "ACTIVE");

        assertThat(data(call("/developer", orgId, Tokens.forUser(userId).signed())))
                .isEqualTo("DEVELOPER");
    }

    @ParameterizedTest
    @CsvSource({
        "viewer, viewer, 200",
        "viewer, developer, 403",
        "viewer, admin, 403",
        "viewer, owner, 403",
        "developer, viewer, 200",
        "developer, developer, 200",
        "developer, admin, 403",
        "admin, developer, 200",
        "admin, admin, 200",
        "admin, owner, 403",
        "owner, owner, 200",
        "owner, viewer, 200"
    })
    void eachRoleAgainstEachFloor(String role, String floor, int expected) throws Exception {
        String orgId = fixtures.newOrg();
        String userId = fixtures.newMember(orgId, role, "ACTIVE");

        MockHttpServletResponse response =
                call("/" + floor, orgId, Tokens.forUser(userId).signed());

        if (expected == 200) {
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(data(response)).isEqualTo(role.toUpperCase(Locale.ROOT));
        } else {
            assertError(response, 403).hasErrorCode("INSUFFICIENT_ROLE");
        }
    }

    @Test
    void aMemberOfAnotherOrgGetsTheSameBodyAsAnUnknownOrg() throws Exception {
        String orgA = fixtures.newOrg();
        String orgB = fixtures.newOrg();
        String userId = fixtures.newMember(orgA, "owner", "ACTIVE");
        fixtures.newMember(orgB, "owner", "ACTIVE");
        String token = Tokens.forUser(userId).signed();
        String unknown = "org-" + UUID.randomUUID();

        assertThat(normalizedNotFound(orgB, token)).isEqualTo(normalizedNotFound(unknown, token));
    }

    @Test
    void aDeletedOrgGetsTheSameBodyAsAnUnknownOrg() throws Exception {
        String orgId = fixtures.newOrg();
        String userId = fixtures.newMember(orgId, "owner", "ACTIVE");
        fixtures.deleteOrg(orgId);
        String token = Tokens.forUser(userId).signed();
        String unknown = "org-" + UUID.randomUUID();

        assertThat(normalizedNotFound(orgId, token)).isEqualTo(normalizedNotFound(unknown, token));
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

        assertError(call("/viewer", orgId, forged), 404).hasErrorCode("ORG_NOT_FOUND");
        assertError(call("/owner", orgId, forged), 404).hasErrorCode("ORG_NOT_FOUND");
    }

    @Test
    void aClaimedOwnerRoleDoesNotLiftAViewer() throws Exception {
        String orgId = fixtures.newOrg();
        String userId = fixtures.newMember(orgId, "viewer", "ACTIVE");
        String token =
                Tokens.forUser(userId).claimingOrg(orgId).claimingRoles("owner").signed();

        assertError(call("/admin", orgId, token), 403).hasErrorCode("INSUFFICIENT_ROLE");
    }

    @Test
    void anAppOfThisOrgIsInScope() throws Exception {
        String orgId = fixtures.newOrg();
        String userId = fixtures.newMember(orgId, "viewer", "ACTIVE");
        UUID appId = fixtures.newApp(orgId, "ACTIVE");

        MockHttpServletResponse response =
                call("/apps/" + appId, orgId, Tokens.forUser(userId).signed());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void anotherOrgsAppADeletedAppAndAnUnknownAppAreTheSameNotFound() throws Exception {
        String orgId = fixtures.newOrg();
        String otherOrg = fixtures.newOrg();
        String token =
                Tokens.forUser(fixtures.newMember(orgId, "owner", "ACTIVE")).signed();
        UUID othersApp = fixtures.newApp(otherOrg, "ACTIVE");
        UUID deletedApp = fixtures.newApp(orgId, "DELETED");

        JsonNode others = normalizedError(call("/apps/" + othersApp, orgId, token), 404, "APP_NOT_FOUND", othersApp);
        JsonNode deleted = normalizedError(call("/apps/" + deletedApp, orgId, token), 404, "APP_NOT_FOUND", deletedApp);
        UUID unknownApp = UUID.randomUUID();
        JsonNode unknown = normalizedError(call("/apps/" + unknownApp, orgId, token), 404, "APP_NOT_FOUND", unknownApp);

        assertThat(others).isEqualTo(unknown);
        assertThat(deleted).isEqualTo(unknown);
    }

    @Test
    void anActiveInstallationLinkOfThisOrgIsInScope() throws Exception {
        String orgId = fixtures.newOrg();
        String token =
                Tokens.forUser(fixtures.newMember(orgId, "viewer", "ACTIVE")).signed();
        long installationId = fixtures.newInstallation();
        fixtures.linkInstallation(installationId, orgId, "ACTIVE");

        MockHttpServletResponse response = call("/installations/" + installationId, orgId, token);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(jsonMapper
                        .readValue(response.getContentAsString(), new TypeReference<ApiResponse<Long>>() {})
                        .data())
                .isEqualTo(installationId);
    }

    @Test
    void anotherOrgsInstallationAndAnUnlinkedOneAreTheSameNotFound() throws Exception {
        String orgId = fixtures.newOrg();
        String otherOrg = fixtures.newOrg();
        String token =
                Tokens.forUser(fixtures.newMember(orgId, "owner", "ACTIVE")).signed();
        long shared = fixtures.newInstallation();
        fixtures.linkInstallation(shared, otherOrg, "ACTIVE");
        long unlinked = fixtures.newInstallation();
        fixtures.linkInstallation(unlinked, orgId, "UNLINKED");
        long unknown = fixtures.newInstallation();

        JsonNode othersBody =
                normalizedError(call("/installations/" + shared, orgId, token), 404, "INSTALLATION_NOT_FOUND", shared);
        JsonNode unlinkedBody = normalizedError(
                call("/installations/" + unlinked, orgId, token), 404, "INSTALLATION_NOT_FOUND", unlinked);
        JsonNode unknownBody = normalizedError(
                call("/installations/" + unknown, orgId, token), 404, "INSTALLATION_NOT_FOUND", unknown);

        assertThat(othersBody).isEqualTo(unknownBody);
        assertThat(unlinkedBody).isEqualTo(unknownBody);
    }

    @Test
    void theCallerEndpointNeedsOnlyAToken() throws Exception {
        MockHttpServletResponse response = mvc.perform(get(PREFIX + ProbeController.BASE + "/caller")
                        .header(
                                "Authorization",
                                "Bearer " + Tokens.forUser("user-7").signed()))
                .andReturn()
                .getResponse();

        assertThat(data(response)).isEqualTo("user-7");
    }

    @Test
    void theWebhookPathIsNotBehindABearerToken() throws Exception {
        MockHttpServletResponse response = WebhookFixtures.delivery("ping.json").post(mvc);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void otherMethodsOnTheWebhookPathStillNeedAToken() throws Exception {
        assertError(
                mvc.perform(get(PREFIX + "/git-integration/webhooks/github"))
                        .andReturn()
                        .getResponse(),
                401);
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
        call("/admin", orgId, Tokens.forUser(viewer).signed());

        for (int i = 0; i < reasons.size(); i++) {
            assertThat(denied(reasons.get(i))).as(reasons.get(i)).isEqualTo(before.get(i) + 1);
        }
    }

    private MockHttpServletResponse call(String path, String orgId, String token) throws Exception {
        return mvc.perform(get(ORG + path, orgId).header("Authorization", "Bearer " + token))
                .andReturn()
                .getResponse();
    }

    private String data(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(200);
        ApiResponse<String> body =
                jsonMapper.readValue(response.getContentAsString(), new TypeReference<ApiResponse<String>>() {});
        PalletAssertions.assertThat(body).isSuccess();
        return body.data();
    }

    private ErrorResponseAssert assertError(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        ErrorResponse body = jsonMapper.readValue(response.getContentAsString(), ErrorResponse.class);
        return PalletAssertions.assertThat(body).isFailure().hasStatusCode(status);
    }

    private JsonNode normalizedNotFound(String orgId, String token) throws Exception {
        return normalizedError(call("/viewer", orgId, token), 404, "ORG_NOT_FOUND", orgId);
    }

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
        Counter counter = meters.find(MetricsCatalog.AUTHZ_DENIED)
                .tag(MetricsCatalog.TAG_REASON, reason)
                .counter();
        return counter == null ? 0 : counter.count();
    }
}
