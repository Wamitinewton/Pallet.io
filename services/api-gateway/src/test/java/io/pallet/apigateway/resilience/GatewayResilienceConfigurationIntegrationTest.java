package io.pallet.apigateway.resilience;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Every route here is declared public so a request's fate depends only on the resilience filters
 * under test, never on token validation ({@link
 * io.pallet.apigateway.security.GatewaySecurityConfigurationIntegrationTest} covers that). The
 * shared {@code pallet.resilience.defaults} are tightened for the whole class so a breaker trips,
 * or a timeout fires, within a couple of requests instead of the production-sized thresholds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("integration")
class GatewayResilienceConfigurationIntegrationTest {

    private static final WireMockServer BACKEND =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeAll
    static void startBackendStub() {
        BACKEND.start();
        BACKEND.stubFor(get(urlEqualTo("/api/v1/breaker/anything"))
                .willReturn(aResponse().withStatus(500)));
        BACKEND.stubFor(get(urlEqualTo("/api/v1/timeout/anything"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(6000)));
        BACKEND.stubFor(get(urlEqualTo("/api/v1/identity/retry-target"))
                .inScenario("retry-then-succeed")
                .whenScenarioStateIs(STARTED)
                .willSetStateTo("retried")
                .willReturn(aResponse().withStatus(500)));
        BACKEND.stubFor(get(urlEqualTo("/api/v1/identity/retry-target"))
                .inScenario("retry-then-succeed")
                .whenScenarioStateIs("retried")
                .willReturn(aResponse().withStatus(200)));
    }

    @AfterAll
    static void stopBackendStub() {
        BACKEND.stop();
    }

    @DynamicPropertySource
    static void gatewayConfig(DynamicPropertyRegistry registry) {
        registry.add("pallet.resilience.defaults.circuit-breaker.minimum-number-of-calls", () -> "2");
        registry.add("pallet.resilience.defaults.circuit-breaker.sliding-window-size", () -> "2");
        registry.add("pallet.resilience.defaults.retry.max-attempts", () -> "1");
        registry.add("pallet.resilience.defaults.time-limiter.timeout", () -> "3s");

        registry.add("pallet.gateway.routes.identity-service.uri", BACKEND::baseUrl);
        registry.add("pallet.gateway.routes.identity-service.path", () -> "/api/v1/identity/**");
        registry.add("pallet.gateway.routes.identity-service.allowed-methods", () -> "GET");
        registry.add("pallet.gateway.routes.identity-service.public-paths[0]", () -> "/api/v1/identity/**");
        // Overrides the tightened default of 1 so this route's own retry budget is what's tested.
        registry.add("pallet.gateway.routes.identity-service.resilience-policy", () -> "identity-service-retry");
        registry.add("pallet.resilience.policies.identity-service-retry.retry.max-attempts", () -> "3");

        registry.add("pallet.gateway.routes.breaker-service.uri", BACKEND::baseUrl);
        registry.add("pallet.gateway.routes.breaker-service.path", () -> "/api/v1/breaker/**");
        registry.add("pallet.gateway.routes.breaker-service.allowed-methods", () -> "GET");
        registry.add("pallet.gateway.routes.breaker-service.public-paths[0]", () -> "/api/v1/breaker/**");
        // A named policy entry is self-contained once it exists — its own unset fields fall back
        // to the Policy record's own defaults, not pallet.resilience.defaults — so every field this
        // test cares about is set explicitly rather than relying on partial inheritance.
        registry.add("pallet.gateway.routes.breaker-service.resilience-policy", () -> "breaker-service-policy");
        registry.add("pallet.resilience.policies.breaker-service-policy.retry.max-attempts", () -> "0");
        registry.add(
                "pallet.resilience.policies.breaker-service-policy.circuit-breaker.minimum-number-of-calls", () -> "2");
        registry.add(
                "pallet.resilience.policies.breaker-service-policy.circuit-breaker.sliding-window-size", () -> "2");

        registry.add("pallet.gateway.routes.timeout-service.uri", BACKEND::baseUrl);
        registry.add("pallet.gateway.routes.timeout-service.path", () -> "/api/v1/timeout/**");
        registry.add("pallet.gateway.routes.timeout-service.allowed-methods", () -> "GET");
        registry.add("pallet.gateway.routes.timeout-service.public-paths[0]", () -> "/api/v1/timeout/**");
        // No resilience-policy set at all: exercises the pallet.resilience.defaults fallback.
    }

    @Test
    void openCircuitReturnsServiceUnavailableWithoutCallingTheBackendAgain() throws Exception {
        // Both calls still reach the stub: the breaker is CLOSED while each of them runs, and only
        // trips once the second failure fills the (deliberately tiny) sliding window.
        send("/api/v1/breaker/anything");
        HttpResponse<String> trippingCall = send("/api/v1/breaker/anything");
        assertThat(trippingCall.statusCode()).isEqualTo(503);
        BACKEND.verify(2, getRequestedFor(urlEqualTo("/api/v1/breaker/anything")));

        HttpResponse<String> rejectedByOpenCircuit = send("/api/v1/breaker/anything");

        assertThat(rejectedByOpenCircuit.statusCode()).isEqualTo(503);
        assertThat(rejectedByOpenCircuit.body())
                .contains("\"success\":false")
                .contains("\"statusCode\":503")
                .contains("\"error\":\"SERVICE_UNAVAILABLE\"");
        BACKEND.verify(2, getRequestedFor(urlEqualTo("/api/v1/breaker/anything")));
    }

    @Test
    void slowBackendPastTheConfiguredTimeoutReturnsGatewayTimeout() throws Exception {
        HttpResponse<String> response = send("/api/v1/timeout/anything");

        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(response.body()).contains("\"error\":\"GATEWAY_TIMEOUT\"");
    }

    @Test
    void retriesOnceThenSucceedsWithinTheConfiguredBudget() throws Exception {
        HttpResponse<String> response = send("/api/v1/identity/retry-target");

        assertThat(response.statusCode()).isEqualTo(200);
        BACKEND.verify(2, getRequestedFor(urlEqualTo("/api/v1/identity/retry-target")));
    }

    private HttpResponse<String> send(String path) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
