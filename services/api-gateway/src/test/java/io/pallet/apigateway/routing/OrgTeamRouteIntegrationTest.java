package io.pallet.apigateway.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import io.pallet.common.test.containers.KeycloakTestTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.import=file:../../config-repo/api-gateway.yml")
@ActiveProfiles("test")
@Import(KeycloakTestContainerConfiguration.class)
@Tag("integration")
class OrgTeamRouteIntegrationTest {

    private static final String BASE = "/api/v1/org-team";
    private static final String ORG_PATH = BASE + "/orgs/acme";

    private static final WireMockServer ORG_TEAM_SERVICE =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeAll
    static void startOrgTeamServiceStub() {
        ORG_TEAM_SERVICE.start();
        ORG_TEAM_SERVICE.stubFor(
                any(urlMatching(BASE + "/.*")).willReturn(aResponse().withStatus(200)));
        ORG_TEAM_SERVICE.stubFor(any(urlEqualTo(ORG_PATH + "/failing-read"))
                .willReturn(aResponse().withStatus(503)));
        ORG_TEAM_SERVICE.stubFor(any(urlEqualTo(ORG_PATH + "/upstream/503"))
                .willReturn(aResponse().withStatus(503)));
        ORG_TEAM_SERVICE.stubFor(any(urlEqualTo(ORG_PATH + "/upstream/429"))
                .willReturn(aResponse().withStatus(429)));
    }

    @AfterAll
    static void stopOrgTeamServiceStub() {
        ORG_TEAM_SERVICE.stop();
    }

    @BeforeEach
    void resetCapturedRequests() {
        ORG_TEAM_SERVICE.resetRequests();
    }

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        registry.add("ORG_TEAM_SERVICE_URI", ORG_TEAM_SERVICE::baseUrl);
        registry.add("pallet.gateway.rate-limit.enabled", () -> "false");
    }

    @Test
    void anAuthenticatedRequestToAnOrgPathIsProxiedWithItsToken() throws Exception {
        String token = ownerToken();

        HttpResponse<String> response = send("GET", ORG_PATH, "Bearer " + token);

        assertThat(response.statusCode()).isEqualTo(200);
        ORG_TEAM_SERVICE.verify(
                getRequestedFor(urlEqualTo(ORG_PATH)).withHeader("Authorization", equalTo("Bearer " + token)));
    }

    @Test
    void anUnauthenticatedRequestToAnOrgPathIsRejectedAtTheEdge() throws Exception {
        HttpResponse<String> response = send("GET", ORG_PATH, null);

        assertThat(response.statusCode()).isEqualTo(401);
        ORG_TEAM_SERVICE.verify(0, anyRequestedFor(urlMatching(".*")));
    }

    @Test
    void anUnauthenticatedRequestToTheInviteListIsRejectedAtTheEdge() throws Exception {
        HttpResponse<String> response = send("GET", ORG_PATH + "/invites", null);

        assertThat(response.statusCode()).isEqualTo(401);
        ORG_TEAM_SERVICE.verify(0, anyRequestedFor(urlMatching(".*")));
    }

    @Test
    void theInvitePreviewIsProxiedWithoutAToken() throws Exception {
        HttpResponse<String> response = send("GET", BASE + "/invites/some-signed-token", null);

        assertThat(response.statusCode()).isEqualTo(200);
        ORG_TEAM_SERVICE.verify(getRequestedFor(urlEqualTo(BASE + "/invites/some-signed-token")));
    }

    @Test
    void thePreviewGlobMatchesExactlyOneSegmentAndNeverAnOrgScopedInvitePath() throws Exception {
        assertThat(send("GET", BASE + "/invites/some/nested", null).statusCode())
                .isEqualTo(401);
        assertThat(send("GET", ORG_PATH + "/invites/some-invite-id", null).statusCode())
                .isEqualTo(401);
        assertThat(send("DELETE", ORG_PATH + "/invites/some-invite-id", null).statusCode())
                .isEqualTo(401);

        ORG_TEAM_SERVICE.verify(0, anyRequestedFor(urlMatching(".*")));
    }

    @Test
    void nothingUnderTheOrgsPrefixIsPublic() throws Exception {
        for (String path :
                new String[] {BASE + "/orgs", ORG_PATH, ORG_PATH + "/members", ORG_PATH + "/teams", ORG_PATH + "/apps"
                }) {
            assertThat(send("GET", path, null).statusCode()).as(path).isEqualTo(401);
        }

        ORG_TEAM_SERVICE.verify(0, anyRequestedFor(urlMatching(".*")));
    }

    @Test
    void theApiDocsAreProxiedWithoutAToken() throws Exception {
        HttpResponse<String> response = send("GET", BASE + "/v3/api-docs", null);

        assertThat(response.statusCode()).isEqualTo(200);
        ORG_TEAM_SERVICE.verify(getRequestedFor(urlEqualTo(BASE + "/v3/api-docs")));
    }

    @Test
    void theDocsHubListsTheServiceWithNoGatewayCodeChange() throws Exception {
        HttpResponse<String> response = send("GET", "/docs", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"url\":\"" + BASE + "/v3/api-docs\"", "\"slug\":\"org-team-service\"");
    }

    @Test
    void aDisallowedMethodIsRejectedBeforeReachingTheService() throws Exception {
        HttpResponse<String> response = send("PUT", ORG_PATH, "Bearer " + ownerToken());

        assertThat(response.statusCode()).isEqualTo(405);
        ORG_TEAM_SERVICE.verify(0, anyRequestedFor(urlMatching(".*")));
    }

    @Test
    void aFailedReadIsRetriedByTheRoutePolicy() throws Exception {
        send("GET", ORG_PATH + "/failing-read", "Bearer " + ownerToken());

        assertThat(ORG_TEAM_SERVICE
                        .findAll(getRequestedFor(urlEqualTo(ORG_PATH + "/failing-read")))
                        .size())
                .isGreaterThan(1);
    }

    @ParameterizedTest
    @CsvSource({"POST,503", "POST,429", "PATCH,503", "PATCH,429", "DELETE,503", "DELETE,429"})
    void aWriteThatDrawsAnUpstreamFailureIsSentExactlyOnce(String method, int status) throws Exception {
        String path = ORG_PATH + "/upstream/" + status;

        HttpResponse<String> response = send(method, path, "Bearer " + ownerToken());

        assertThat(response.statusCode()).isEqualTo(status);
        ORG_TEAM_SERVICE.verify(1, anyRequestedFor(urlEqualTo(path)));
    }

    private static String ownerToken() {
        return KeycloakTestTokens.passwordGrantToken(
                KeycloakTestTokens.OWNER_USERNAME, KeycloakTestTokens.FIXTURE_PASSWORD);
    }

    private HttpResponse<String> send(String method, String path, String authorizationHeader) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (authorizationHeader != null) {
            request.header("Authorization", authorizationHeader);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
