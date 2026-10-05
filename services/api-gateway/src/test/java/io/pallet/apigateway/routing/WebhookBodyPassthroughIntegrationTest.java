package io.pallet.apigateway.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.import=file:../../config-repo/api-gateway.yml")
@ActiveProfiles("test")
@Import(RedisTestContainerConfiguration.class)
@Tag("integration")
class WebhookBodyPassthroughIntegrationTest {

    private static final String WEBHOOK = "/api/v1/git-integration/webhooks/github";
    private static final String API_DOCS = "/api/v1/git-integration/v3/api-docs";
    private static final String SIGNATURE = "sha256=" + "ab".repeat(32);
    private static final String USER_AGENT = "GitHub-Hookshot/abc1234";
    private static final byte[] PAYLOAD = """
            {  "zeta" :1,"action":"opened" ,
              "installation": {"id": 42},
            \t"message": "caf\\u00e9 \\u0000 é\\n", "alpha": [ 3 , 2,1 ] }
            """.getBytes(StandardCharsets.UTF_8);

    private static final WireMockServer GIT_INTEGRATION_SERVICE =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @LocalServerPort
    private int port;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeAll
    static void startGitIntegrationServiceStub() {
        GIT_INTEGRATION_SERVICE.start();
        GIT_INTEGRATION_SERVICE.stubFor(any(urlMatching("/api/v1/git-integration/.*"))
                .willReturn(aResponse().withStatus(202)));
    }

    @AfterAll
    static void stopGitIntegrationServiceStub() {
        GIT_INTEGRATION_SERVICE.stop();
    }

    @BeforeEach
    void resetState() {
        GIT_INTEGRATION_SERVICE.resetRequests();
        Set<String> counters = redisTemplate.keys("gateway:ratelimit:*");
        if (counters != null && !counters.isEmpty()) {
            redisTemplate.delete(counters);
        }
    }

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        registry.add("GIT_INTEGRATION_SERVICE_URI", GIT_INTEGRATION_SERVICE::baseUrl);
        registry.add("pallet.gateway.rate-limit.enabled", () -> "true");
        // A refill this slow adds nothing during the test, so the 61st docs request is reliably over.
        registry.add("pallet.gateway.rate-limit.capacity", () -> "60");
        registry.add("pallet.gateway.rate-limit.window", () -> "1h");
    }

    @Test
    void theWebhookBodyAndGitHubHeadersReachTheServiceUnchangedWithoutAToken() throws Exception {
        String deliveryId = UUID.randomUUID().toString();

        HttpResponse<String> response = sendWebhook(PAYLOAD, deliveryId);

        assertThat(response.statusCode()).isEqualTo(202);
        GIT_INTEGRATION_SERVICE.verify(postRequestedFor(urlEqualTo(WEBHOOK))
                .withRequestBody(binaryEqualTo(PAYLOAD))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("X-Hub-Signature-256", equalTo(SIGNATURE))
                .withHeader("X-GitHub-Event", equalTo("push"))
                .withHeader("X-GitHub-Delivery", equalTo(deliveryId))
                .withHeader("User-Agent", equalTo(USER_AGENT)));
    }

    @Test
    void aBodyOverTomcatsDefaultLimitsStillPassesThroughByteForByte() throws Exception {
        byte[] large = new byte[5 * 1024 * 1024];
        Arrays.fill(large, (byte) ' ');
        large[0] = '{';
        large[large.length - 1] = '}';

        HttpResponse<String> response = sendWebhook(large, UUID.randomUUID().toString());

        assertThat(response.statusCode()).isEqualTo(202);
        GIT_INTEGRATION_SERVICE.verify(postRequestedFor(urlEqualTo(WEBHOOK)).withRequestBody(binaryEqualTo(large)));
    }

    @Test
    void theWebhookIsExemptFromTheEdgeLimitWhileOtherPublicPathsKeepIt() throws Exception {
        for (int i = 0; i < 200; i++) {
            assertThat(sendWebhook(PAYLOAD, UUID.randomUUID().toString()).statusCode())
                    .as("delivery %d", i)
                    .isEqualTo(202);
        }
        GIT_INTEGRATION_SERVICE.verify(200, postRequestedFor(urlEqualTo(WEBHOOK)));

        for (int i = 0; i < 60; i++) {
            assertThat(get(API_DOCS).statusCode()).as("docs request %d", i).isEqualTo(202);
        }
        assertThat(get(API_DOCS).statusCode()).isEqualTo(429);
    }

    private HttpResponse<String> sendWebhook(byte[] body, String deliveryId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + WEBHOOK))
                .header("Content-Type", "application/json")
                .header("X-Hub-Signature-256", SIGNATURE)
                .header("X-GitHub-Event", "push")
                .header("X-GitHub-Delivery", deliveryId)
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
