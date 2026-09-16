package io.pallet.apigateway.security;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(KeycloakTestContainerConfiguration.class)
@Tag("integration")
class GatewaySecurityConfigurationIntegrationTest {

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

    @BeforeEach
    void resetCapturedRequests() {
        IDENTITY_SERVICE.resetRequests();
    }

    @DynamicPropertySource
    static void gatewayRoutes(DynamicPropertyRegistry registry) {
        registry.add("pallet.gateway.routes.identity-service.uri", IDENTITY_SERVICE::baseUrl);
        registry.add("pallet.gateway.routes.identity-service.path", () -> "/api/v1/identity/**");
        registry.add("pallet.gateway.routes.identity-service.allowed-methods", () -> "GET,POST,PATCH,DELETE");
        registry.add("pallet.gateway.routes.identity-service.public-paths[0]", () -> "/api/v1/identity/auth/login");
        registry.add("pallet.gateway.routes.identity-service.resilience-policy", () -> "identity-service");
    }

    @Test
    void requestWithNoTokenToAProtectedPathIsRejectedBeforeReachingTheBackend() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/identity/users/me", null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).isPresent();
        assertThat(response.body())
                .contains("\"success\":false")
                .contains("\"error\":\"AUTHENTICATION_REQUIRED\"")
                .contains("\"statusCode\":401");
        IDENTITY_SERVICE.verify(0, getRequestedFor(urlEqualTo("/api/v1/identity/users/me")));
    }

    @Test
    void requestWithAMalformedTokenToAProtectedPathIsRejectedBeforeReachingTheBackend() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/identity/users/me", "Bearer not-a-real-jwt");

        assertThat(response.statusCode()).isEqualTo(401);
        IDENTITY_SERVICE.verify(0, getRequestedFor(urlEqualTo("/api/v1/identity/users/me")));
    }

    @Test
    void requestWithAValidTokenToAProtectedPathReachesTheBackendWithTheOriginalAuthorizationHeader() throws Exception {
        String accessToken = KeycloakTestTokens.passwordGrantToken(
                KeycloakTestTokens.OWNER_USERNAME, KeycloakTestTokens.FIXTURE_PASSWORD);

        HttpResponse<String> response = send("GET", "/api/v1/identity/users/me", "Bearer " + accessToken);

        assertThat(response.statusCode()).isEqualTo(200);
        IDENTITY_SERVICE.verify(getRequestedFor(urlEqualTo("/api/v1/identity/users/me"))
                .withHeader("Authorization", equalTo("Bearer " + accessToken)));
    }

    @Test
    void requestWithNoTokenToAPublicPathReachesTheBackendUnauthenticated() throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/identity/auth/login", null);

        assertThat(response.statusCode()).isEqualTo(200);
        IDENTITY_SERVICE.verify(postRequestedFor(urlEqualTo("/api/v1/identity/auth/login")));
    }

    @Test
    void actuatorHealthIsReachableWithNoToken() throws Exception {
        HttpResponse<String> response = send("GET", "/actuator/health", null);

        assertThat(response.statusCode()).isEqualTo(200);
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
