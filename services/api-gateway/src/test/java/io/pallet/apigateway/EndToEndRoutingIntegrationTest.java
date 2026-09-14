package io.pallet.apigateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.observability.CorrelationId;
import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import io.pallet.common.test.containers.KeycloakTestTokens;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The hardening pass across checkpoints 2-5: every filter in the real chain together, not each
 * checkpoint's own isolated route config. Each scenario gets its own nested context because the
 * dependency each one perturbs — an open circuit, an over-budget counter, an unreachable Redis —
 * would otherwise leak into the others sharing the same route or the same rate-limit key.
 */
@Tag("integration")
class EndToEndRoutingIntegrationTest {

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("test")
    @Import(KeycloakTestContainerConfiguration.class)
    @Tag("integration")
    class FullChainWithHealthyDependencies {

        private static final WireMockServer BACKEND =
                new WireMockServer(WireMockConfiguration.options().dynamicPort());

        @LocalServerPort
        private int port;

        private final HttpClient httpClient = HttpClient.newHttpClient();

        @BeforeAll
        static void startBackendStub() {
            BACKEND.start();
            BACKEND.stubFor(get(urlEqualTo("/api/v1/identity/users/me"))
                    .willReturn(aResponse().withStatus(200)));
            BACKEND.stubFor(get(urlEqualTo("/api/v1/breaker/anything"))
                    .willReturn(aResponse().withStatus(500)));
        }

        @AfterAll
        static void stopBackendStub() {
            BACKEND.stop();
        }

        @DynamicPropertySource
        static void gatewayConfig(DynamicPropertyRegistry registry) {
            registry.add("pallet.gateway.routes.identity-service.uri", BACKEND::baseUrl);
            registry.add("pallet.gateway.routes.identity-service.path", () -> "/api/v1/identity/**");
            registry.add("pallet.gateway.routes.identity-service.allowed-methods", () -> "GET");
            registry.add("pallet.gateway.routes.identity-service.resilience-policy", () -> "identity-service");

            registry.add("pallet.gateway.routes.breaker-service.uri", BACKEND::baseUrl);
            registry.add("pallet.gateway.routes.breaker-service.path", () -> "/api/v1/breaker/**");
            registry.add("pallet.gateway.routes.breaker-service.allowed-methods", () -> "GET");
            registry.add("pallet.gateway.routes.breaker-service.public-paths[0]", () -> "/api/v1/breaker/**");
            registry.add("pallet.gateway.routes.breaker-service.resilience-policy", () -> "breaker-service-policy");
            registry.add("pallet.resilience.policies.breaker-service-policy.retry.max-attempts", () -> "0");
            registry.add(
                    "pallet.resilience.policies.breaker-service-policy.circuit-breaker.minimum-number-of-calls",
                    () -> "2");
            registry.add(
                    "pallet.resilience.policies.breaker-service-policy.circuit-breaker.sliding-window-size", () -> "2");
        }

        @Test
        void aRequestWithNoInboundCorrelationIdGetsTheSameIdInTheResponseAndTheBackendRequest() throws Exception {
            String accessToken = KeycloakTestTokens.passwordGrantToken(
                    KeycloakTestTokens.OWNER_USERNAME, KeycloakTestTokens.FIXTURE_PASSWORD);

            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/identity/users/me"))
                            .header("Authorization", "Bearer " + accessToken)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            String correlationId =
                    response.headers().firstValue(CorrelationId.HEADER).orElseThrow();
            BACKEND.verify(getRequestedFor(urlEqualTo("/api/v1/identity/users/me"))
                    .withHeader(CorrelationId.HEADER, equalTo(correlationId)));
        }

        @Test
        void openCircuitReturnsServiceUnavailableWithoutCallingTheBackendAgain() throws Exception {
            send("GET", "/api/v1/breaker/anything");
            HttpResponse<String> trippingCall = send("GET", "/api/v1/breaker/anything");
            assertThat(trippingCall.statusCode()).isEqualTo(503);

            HttpResponse<String> rejectedByOpenCircuit = send("GET", "/api/v1/breaker/anything");

            assertThat(rejectedByOpenCircuit.statusCode()).isEqualTo(503);
            BACKEND.verify(2, getRequestedFor(urlEqualTo("/api/v1/breaker/anything")));
        }

        @Test
        void aDisallowedMethodOnARoutedPathReturnsMethodNotAllowedBeforeReachingTheBackend() throws Exception {
            HttpResponse<String> response = send("PUT", "/api/v1/breaker/anything");

            assertThat(response.statusCode()).isEqualTo(405);
            BACKEND.verify(0, putRequestedFor(urlEqualTo("/api/v1/breaker/anything")));
        }

        private HttpResponse<String> send(String method, String path) throws Exception {
            return httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                            .method(method, HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("test")
    @Import(RedisTestContainerConfiguration.class)
    @Tag("integration")
    class OverRateLimitBudget {

        private static final WireMockServer BACKEND =
                new WireMockServer(WireMockConfiguration.options().dynamicPort());

        @LocalServerPort
        private int port;

        private final HttpClient httpClient = HttpClient.newHttpClient();

        @BeforeAll
        static void startBackendStub() {
            BACKEND.start();
            BACKEND.stubFor(any(urlMatching("/api/v1/limited/.*"))
                    .willReturn(aResponse().withStatus(200)));
        }

        @AfterAll
        static void stopBackendStub() {
            BACKEND.stop();
        }

        @DynamicPropertySource
        static void gatewayConfig(DynamicPropertyRegistry registry) {
            registry.add("pallet.gateway.rate-limit.enabled", () -> "true");
            registry.add("pallet.gateway.rate-limit.capacity", () -> "3");
            registry.add("pallet.gateway.rate-limit.window", () -> "5s");

            registry.add("pallet.gateway.routes.limited-service.uri", BACKEND::baseUrl);
            registry.add("pallet.gateway.routes.limited-service.path", () -> "/api/v1/limited/**");
            registry.add("pallet.gateway.routes.limited-service.allowed-methods", () -> "GET");
            registry.add("pallet.gateway.routes.limited-service.public-paths[0]", () -> "/api/v1/limited/**");
            registry.add("pallet.gateway.routes.limited-service.resilience-policy", () -> "limited-service");
        }

        @Test
        void requestsWithinBudgetReachTheBackendThenAnOverBudgetRequestIsRejectedBeforeIt() throws Exception {
            for (int i = 0; i < 3; i++) {
                assertThat(send().statusCode()).isEqualTo(200);
            }

            HttpResponse<String> rejected = send();

            assertThat(rejected.statusCode()).isEqualTo(429);
            BACKEND.verify(3, getRequestedFor(urlEqualTo("/api/v1/limited/anything")));
        }

        private HttpResponse<String> send() throws Exception {
            return httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/limited/anything"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("test")
    @Tag("integration")
    class RedisUnavailable {

        private static final WireMockServer BACKEND =
                new WireMockServer(WireMockConfiguration.options().dynamicPort());

        @LocalServerPort
        private int port;

        @Autowired
        private MeterRegistry meterRegistry;

        private final HttpClient httpClient = HttpClient.newHttpClient();

        @BeforeAll
        static void startBackendStub() {
            BACKEND.start();
            BACKEND.stubFor(any(urlMatching("/api/v1/failopen/.*"))
                    .willReturn(aResponse().withStatus(200)));
        }

        @AfterAll
        static void stopBackendStub() {
            BACKEND.stop();
        }

        @DynamicPropertySource
        static void gatewayConfig(DynamicPropertyRegistry registry) {
            // Nothing listens on this port; Redis commands fail fast with a connection error
            // rather than the fail-open path depending on Redis actually being reachable and then
            // stopped mid-test.
            registry.add("spring.data.redis.host", () -> "localhost");
            registry.add("spring.data.redis.port", () -> "1");
            registry.add("pallet.gateway.rate-limit.enabled", () -> "true");
            registry.add("pallet.gateway.rate-limit.capacity", () -> "60");
            registry.add("pallet.gateway.rate-limit.window", () -> "1m");

            registry.add("pallet.gateway.routes.failopen-service.uri", BACKEND::baseUrl);
            registry.add("pallet.gateway.routes.failopen-service.path", () -> "/api/v1/failopen/**");
            registry.add("pallet.gateway.routes.failopen-service.allowed-methods", () -> "GET");
            registry.add("pallet.gateway.routes.failopen-service.public-paths[0]", () -> "/api/v1/failopen/**");
            registry.add("pallet.gateway.routes.failopen-service.resilience-policy", () -> "failopen-service");
        }

        @Test
        void aRequestStillSucceedsWhenRedisIsUnreachable() throws Exception {
            double before =
                    meterRegistry.counter("gateway.ratelimit.unavailable").count();

            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/failopen/anything"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(meterRegistry.counter("gateway.ratelimit.unavailable").count())
                    .isGreaterThan(before);
        }
    }
}
