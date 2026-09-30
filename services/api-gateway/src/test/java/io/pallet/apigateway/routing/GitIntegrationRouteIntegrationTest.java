package io.pallet.apigateway.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
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
class GitIntegrationRouteIntegrationTest {

    private static final String BASE = "/api/v1/git-integration";
    private static final String ORG_PATH = BASE + "/orgs/acme";
    private static final String REPO_LINK = ORG_PATH + "/apps/app-1/repo-link";

    private static final WireMockServer GIT_INTEGRATION_SERVICE =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeAll
    static void startGitIntegrationServiceStub() {
        GIT_INTEGRATION_SERVICE.start();
        GIT_INTEGRATION_SERVICE.stubFor(
                any(urlMatching(BASE + "/.*")).willReturn(aResponse().withStatus(200)));
        GIT_INTEGRATION_SERVICE.stubFor(any(urlEqualTo(ORG_PATH + "/upstream/503"))
                .willReturn(aResponse().withStatus(503)));
    }

    @AfterAll
    static void stopGitIntegrationServiceStub() {
        GIT_INTEGRATION_SERVICE.stop();
    }

    @BeforeEach
    void resetCapturedRequests() {
        GIT_INTEGRATION_SERVICE.resetRequests();
    }

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        registry.add("GIT_INTEGRATION_SERVICE_URI", GIT_INTEGRATION_SERVICE::baseUrl);
        registry.add("pallet.gateway.rate-limit.enabled", () -> "false");
    }

    @Test
    void anAuthenticatedRequestIsProxiedWithItsToken() throws Exception {
        String token = ownerToken();

        HttpResponse<String> response = send("GET", ORG_PATH + "/github/installations", "Bearer " + token);

        assertThat(response.statusCode()).isEqualTo(200);
        GIT_INTEGRATION_SERVICE.verify(getRequestedFor(urlEqualTo(ORG_PATH + "/github/installations"))
                .withHeader("Authorization", equalTo("Bearer " + token)));
    }

    @Test
    void theRepoLinkPutIsAllowedThroughTheEdge() throws Exception {
        HttpResponse<String> response = send("PUT", REPO_LINK, "Bearer " + ownerToken());

        assertThat(response.statusCode()).isEqualTo(200);
        GIT_INTEGRATION_SERVICE.verify(putRequestedFor(urlEqualTo(REPO_LINK)));
    }

    @Test
    void nothingButTheWebhookAndTheDocsIsPublic() throws Exception {
        for (String path : new String[] {
            ORG_PATH + "/github/installations",
            REPO_LINK,
            BASE + "/github/session",
            BASE + "/github/installations",
            BASE + "/webhooks/github/extra",
            BASE + "/webhooks/gitlab"
        }) {
            assertThat(send("GET", path, null).statusCode()).as(path).isEqualTo(401);
        }

        GIT_INTEGRATION_SERVICE.verify(0, anyRequestedFor(urlMatching(".*")));
    }

    @Test
    void theApiDocsAreProxiedWithoutAToken() throws Exception {
        HttpResponse<String> response = send("GET", BASE + "/v3/api-docs", null);

        assertThat(response.statusCode()).isEqualTo(200);
        GIT_INTEGRATION_SERVICE.verify(getRequestedFor(urlEqualTo(BASE + "/v3/api-docs")));
    }

    @Test
    void theDocsHubListsTheService() throws Exception {
        HttpResponse<String> response = send("GET", "/docs", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"url\":\"" + BASE + "/v3/api-docs\"", "\"slug\":\"git-integration-service\"");
    }

    @ParameterizedTest
    @CsvSource({"POST", "PUT", "PATCH", "DELETE"})
    void aWriteThatDrawsAnUpstreamFailureIsSentExactlyOnce(String method) throws Exception {
        String path = ORG_PATH + "/upstream/503";

        HttpResponse<String> response = send(method, path, "Bearer " + ownerToken());

        assertThat(response.statusCode()).isEqualTo(503);
        GIT_INTEGRATION_SERVICE.verify(1, anyRequestedFor(urlEqualTo(path)));
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
