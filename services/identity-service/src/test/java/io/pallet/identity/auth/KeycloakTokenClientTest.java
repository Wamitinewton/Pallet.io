package io.pallet.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.pallet.common.error.ExternalServiceException;
import io.pallet.common.resilience.ExternalCall;
import io.pallet.common.resilience.ExternalCallExecutor;
import io.pallet.common.resilience.ResilienceProperties;
import io.pallet.common.resilience.ResilienceRegistries;
import io.pallet.identity.config.IdentityServiceProperties;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link KeycloakTokenClient}'s response classification against a real, if minimal, HTTP
 * server standing in for Keycloak's token endpoint — no Testcontainers needed to prove that a
 * successful exchange carrying {@code invalid_grant} is classified correctly and never retried,
 * while a genuine connection failure still goes through {@code ExternalCall}'s normal resilience
 * path. {@link AuthControllerIntegrationTest} covers the same distinction against a real Keycloak.
 */
class KeycloakTokenClientTest {

    private static final String REALM = "test-realm";

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void accountNotFullySetUpIsClassifiedAsEmailNotVerified() throws IOException {
        startTokenServer(400, "{\"error\":\"invalid_grant\",\"error_description\":\"Account is not fully set up\"}");

        assertThatThrownBy(() -> clientFor(server).passwordGrant("user@example.test", "irrelevant"))
                .isInstanceOf(EmailNotVerifiedException.class);
    }

    @Test
    void ordinaryInvalidGrantIsClassifiedAsInvalidCredentials() throws IOException {
        startTokenServer(401, "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid user credentials\"}");

        assertThatThrownBy(() -> clientFor(server).passwordGrant("user@example.test", "wrong"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void successfulGrantDeserializesTheTokenResponse() throws IOException {
        startTokenServer(
                200,
                "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":60,"
                        + "\"refresh_expires_in\":1800,\"token_type\":\"Bearer\"}");

        TokenResponse response = clientFor(server).passwordGrant("user@example.test", "pw");

        assertThat(response.accessToken()).isEqualTo("a");
        assertThat(response.refreshToken()).isEqualTo("r");
        assertThat(response.expiresIn()).isEqualTo(60);
        assertThat(response.refreshExpiresIn()).isEqualTo(1800);
        assertThat(response.tokenType()).isEqualTo("Bearer");
    }

    @Test
    void aGenuineConnectionFailureIsRetriedThenSurfacesAsExternalServiceException() throws IOException {
        IdentityServiceProperties properties = propertiesFor("http://127.0.0.1:" + closedPort());

        assertThatThrownBy(() -> new KeycloakTokenClient(properties, externalCall())
                        .passwordGrant("user@example.test", "irrelevant"))
                .isInstanceOf(ExternalServiceException.class);
    }

    private void startTokenServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/realms/" + REALM + "/protocol/openid-connect/token", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(bytes);
            }
        });
        server.start();
    }

    private KeycloakTokenClient clientFor(HttpServer tokenServer) {
        String serverUrl = "http://127.0.0.1:" + tokenServer.getAddress().getPort();
        return new KeycloakTokenClient(propertiesFor(serverUrl), externalCall());
    }

    private IdentityServiceProperties propertiesFor(String serverUrl) {
        return new IdentityServiceProperties(
                new IdentityServiceProperties.KeycloakAdmin(serverUrl, REALM, "admin", "secret", "pallet-test-client"),
                new IdentityServiceProperties.EmailVerification(Duration.ofMinutes(15), 5),
                new IdentityServiceProperties.PasswordReset(Duration.ofHours(1), "http://localhost:5173"));
    }

    private ExternalCall externalCall() {
        ResilienceRegistries registries = new ResilienceRegistries(new ResilienceProperties(
                new ResilienceProperties.Policy(
                        new ResilienceProperties.CircuitBreaker(
                                50, 10, 10, Duration.ofSeconds(30), 3, 50, Duration.ofSeconds(5)),
                        new ResilienceProperties.Retry(3, Duration.ofMillis(10), 1.0, Duration.ofMillis(50)),
                        new ResilienceProperties.TimeLimiter(Duration.ofSeconds(2), true)),
                Map.of()));
        return new ExternalCallExecutor(registries, executor);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
