package io.pallet.apigateway.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
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
class BuildQueueRouteIntegrationTest {

    private static final String BASE = "/api/v1/build-queue";
    private static final String ORG_PATH = BASE + "/orgs/o";
    private static final String QUEUE = ORG_PATH + "/queue";
    private static final String SLOW_CANCEL = ORG_PATH + "/builds/slow/cancel";

    /** Above the route's 3 s policy and below the 6 s default, so only the route's own timeout can fire. */
    private static final int SLOWER_THAN_THE_ROUTE_TIMEOUT_MS = 4000;

    private static final WireMockServer BUILD_QUEUE_SERVICE =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeAll
    static void startBuildQueueServiceStub() {
        BUILD_QUEUE_SERVICE.start();
        BUILD_QUEUE_SERVICE.stubFor(
                any(urlMatching(BASE + "/.*")).willReturn(aResponse().withStatus(200)));
        BUILD_QUEUE_SERVICE.stubFor(any(urlEqualTo(SLOW_CANCEL))
                .willReturn(aResponse().withStatus(200).withFixedDelay(SLOWER_THAN_THE_ROUTE_TIMEOUT_MS)));
    }

    @AfterAll
    static void stopBuildQueueServiceStub() {
        BUILD_QUEUE_SERVICE.stop();
    }

    @BeforeEach
    void resetCapturedRequests() {
        BUILD_QUEUE_SERVICE.resetRequests();
    }

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        registry.add("BUILD_QUEUE_SERVICE_URI", BUILD_QUEUE_SERVICE::baseUrl);
        registry.add("pallet.gateway.rate-limit.enabled", () -> "false");
    }

    @Test
    void anAuthenticatedRequestIsProxiedWithItsToken() throws Exception {
        String token = ownerToken();

        HttpResponse<String> response = send("GET", QUEUE, "Bearer " + token);

        assertThat(response.statusCode()).isEqualTo(200);
        BUILD_QUEUE_SERVICE.verify(
                getRequestedFor(urlEqualTo(QUEUE)).withHeader("Authorization", equalTo("Bearer " + token)));
    }

    @Test
    void theCancelPostIsAllowedThroughTheEdge() throws Exception {
        String cancel = ORG_PATH + "/builds/b/cancel";

        HttpResponse<String> response = send("POST", cancel, "Bearer " + ownerToken());

        assertThat(response.statusCode()).isEqualTo(200);
        BUILD_QUEUE_SERVICE.verify(postRequestedFor(urlEqualTo(cancel)));
    }

    @Test
    void nothingButTheDocsIsPublic() throws Exception {
        for (String path :
                new String[] {QUEUE, ORG_PATH + "/apps/a/builds", ORG_PATH + "/builds/b", BASE + "/v3/api-docs/extra"
                }) {
            assertThat(send("GET", path, null).statusCode()).as(path).isEqualTo(401);
        }
        assertThat(send("POST", ORG_PATH + "/builds/b/cancel", null).statusCode())
                .isEqualTo(401);

        BUILD_QUEUE_SERVICE.verify(0, anyRequestedFor(urlMatching(".*")));
    }

    @ParameterizedTest
    @CsvSource({"PUT", "DELETE"})
    void aMethodTheServiceDoesNotUseIsRejectedBeforeReachingIt(String method) throws Exception {
        HttpResponse<String> response = send(method, ORG_PATH + "/builds/b", "Bearer " + ownerToken());

        assertThat(response.statusCode()).isEqualTo(405);
        BUILD_QUEUE_SERVICE.verify(0, anyRequestedFor(urlMatching(".*")));
    }

    @Test
    void theApiDocsAreProxiedWithoutAToken() throws Exception {
        HttpResponse<String> response = send("GET", BASE + "/v3/api-docs", null);

        assertThat(response.statusCode()).isEqualTo(200);
        BUILD_QUEUE_SERVICE.verify(getRequestedFor(urlEqualTo(BASE + "/v3/api-docs")));
    }

    @Test
    void theDocsHubListsTheService() throws Exception {
        HttpResponse<String> response = send("GET", "/docs", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"url\":\"" + BASE + "/v3/api-docs\"", "\"slug\":\"build-queue-service\"");
    }

    @Test
    void aDownstreamSlowerThanTheRouteTimeoutGetsTheTimeoutEnvelopeAndIsNotRetried() throws Exception {
        HttpResponse<String> response = send("POST", SLOW_CANCEL, "Bearer " + ownerToken());

        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(response.body())
                .contains("\"success\":false")
                .contains("\"statusCode\":504")
                .contains("\"error\":\"GATEWAY_TIMEOUT\"");
        BUILD_QUEUE_SERVICE.verify(1, anyRequestedFor(urlEqualTo(SLOW_CANCEL)));
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
