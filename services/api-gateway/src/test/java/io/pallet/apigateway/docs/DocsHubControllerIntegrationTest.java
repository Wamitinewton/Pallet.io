package io.pallet.apigateway.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Verifies the hub is built purely from {@code pallet.gateway.routes} — three fixture routes,
 * including one with no {@code -service} suffix, stand in for "whatever is currently registered."
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("integration")
class DocsHubControllerIntegrationTest {

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void gatewayRoutes(DynamicPropertyRegistry registry) {
        registry.add("pallet.gateway.routes.identity-service.uri", () -> "http://localhost:1");
        registry.add("pallet.gateway.routes.identity-service.path", () -> "/api/v1/identity/**");
        registry.add("pallet.gateway.routes.identity-service.allowed-methods", () -> "GET");
        registry.add("pallet.gateway.routes.identity-service.public-paths[0]", () -> "/api/v1/identity/v3/api-docs");
        registry.add("pallet.gateway.routes.identity-service.resilience-policy", () -> "identity-service");

        registry.add("pallet.gateway.routes.notification-service.uri", () -> "http://localhost:1");
        registry.add("pallet.gateway.routes.notification-service.path", () -> "/api/v1/notification/**");
        registry.add("pallet.gateway.routes.notification-service.allowed-methods", () -> "GET");
        registry.add(
                "pallet.gateway.routes.notification-service.public-paths[0]", () -> "/api/v1/notification/v3/api-docs");
        registry.add("pallet.gateway.routes.notification-service.resilience-policy", () -> "notification-service");

        registry.add("pallet.gateway.routes.billing.uri", () -> "http://localhost:1");
        registry.add("pallet.gateway.routes.billing.path", () -> "/api/v1/billing/**");
        registry.add("pallet.gateway.routes.billing.allowed-methods", () -> "GET");
        registry.add("pallet.gateway.routes.billing.public-paths[0]", () -> "/api/v1/billing/v3/api-docs");
        registry.add("pallet.gateway.routes.billing.resilience-policy", () -> "billing");
    }

    @Test
    void rendersASourceForEveryConfiguredRouteWithHumanizedTitles() throws Exception {
        HttpResponse<String> response = get("/docs");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(contentType -> assertThat(contentType).startsWith(MediaType.TEXT_HTML_VALUE));
        assertThat(response.body())
                .contains("\"url\":\"/api/v1/identity/v3/api-docs\"", "\"title\":\"Identity\"")
                .contains("\"url\":\"/api/v1/notification/v3/api-docs\"", "\"title\":\"Notification\"")
                .contains("\"url\":\"/api/v1/billing/v3/api-docs\"", "\"title\":\"Billing\"");
    }

    @Test
    void docsIsReachableWithNoAuthenticationToken() throws Exception {
        HttpResponse<String> response = get("/docs");

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void servesTheVendoredScalarBundle() throws Exception {
        HttpResponse<String> response = get("/docs/scalar.js");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isNotBlank();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
