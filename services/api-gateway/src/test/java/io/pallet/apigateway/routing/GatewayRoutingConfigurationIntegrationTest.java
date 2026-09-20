package io.pallet.apigateway.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Verifies routing and the method allowlist in isolation from edge auth: the whole
 * {@code identity-service} route is declared public here so a request's fate depends only on
 * routing/method matching, never on token validation, which {@link
 * io.pallet.apigateway.security.GatewaySecurityConfigurationIntegrationTest} covers instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("integration")
class GatewayRoutingConfigurationIntegrationTest {

    private static final WireMockServer IDENTITY_SERVICE =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeAll
    static void startIdentityServiceStub() {
        IDENTITY_SERVICE.start();
        IDENTITY_SERVICE.stubFor(
                any(urlMatching("/api/v1/identity/.*")).willReturn(aResponse().withStatus(200)));
    }

    @AfterAll
    static void stopIdentityServiceStub() {
        IDENTITY_SERVICE.stop();
    }

    @DynamicPropertySource
    static void gatewayRoutes(DynamicPropertyRegistry registry) {
        registry.add("pallet.gateway.routes.identity-service.uri", IDENTITY_SERVICE::baseUrl);
        registry.add("pallet.gateway.routes.identity-service.path", () -> "/api/v1/identity/**");
        registry.add("pallet.gateway.routes.identity-service.allowed-methods", () -> "GET,POST,PATCH,DELETE");
        registry.add("pallet.gateway.routes.identity-service.public-paths[0]", () -> "/api/v1/identity/**");
        // Also public: lets returnsNotFoundForAnUnroutedPath exercise "no route matches" on its
        // own, rather than an edge-auth 401 masking the routing behavior under test.
        registry.add("pallet.gateway.routes.identity-service.public-paths[1]", () -> "/api/v1/org-team/**");
        registry.add("pallet.gateway.routes.identity-service.resilience-policy", () -> "identity-service");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/v1/identity/signup",
                "/api/v1/identity/invites/some-token/accept",
                "/api/v1/identity/auth/login",
                "/api/v1/identity/auth/refresh",
                "/api/v1/identity/auth/email/verify",
                "/api/v1/identity/auth/email/resend-verification",
                "/api/v1/identity/auth/password/forgot",
                "/api/v1/identity/auth/password/reset"
            })
    void proxiesEachPublicPathToIdentityServiceWithPathAndMethodUnchanged(String path) throws Exception {
        HttpResponse<String> response = send("POST", path);

        assertThat(response.statusCode()).isEqualTo(200);
        IDENTITY_SERVICE.verify(postRequestedFor(urlEqualTo(path)));
    }

    @Test
    void proxiesAProtectedPathToIdentityServiceWithPathAndMethodUnchanged() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/identity/users/me");

        assertThat(response.statusCode()).isEqualTo(200);
        IDENTITY_SERVICE.verify(getRequestedFor(urlEqualTo("/api/v1/identity/users/me")));
    }

    @Test
    void returnsNotFoundForAnUnroutedPath() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/org-team/anything");

        assertThat(response.statusCode()).isEqualTo(404);
        IDENTITY_SERVICE.verify(0, getRequestedFor(urlEqualTo("/api/v1/org-team/anything")));
    }

    @Test
    void returnsMethodNotAllowedForADisallowedMethodOnARoutedPath() throws Exception {
        HttpResponse<String> response = send("PUT", "/api/v1/identity/signup");

        assertThat(response.statusCode()).isEqualTo(405);
        IDENTITY_SERVICE.verify(0, putRequestedFor(urlEqualTo("/api/v1/identity/signup")));
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
