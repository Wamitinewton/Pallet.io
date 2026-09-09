package io.pallet.common.test.containers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fetches a real, Keycloak-signed access token for one of {@link KeycloakContainerHolder}'s two
 * fixture users via the OAuth2 resource-owner password grant. The realm's
 * {@code pallet-test-client} is public with direct-access-grants enabled, so this needs no
 * client secret.
 *
 * <p>Requires {@link KeycloakTestContainerConfiguration} to be imported into the test's context
 * only insofar as the realm needs to exist and be reachable; this class talks to the container
 * directly and doesn't need the Spring context wired up to work.
 */
public final class KeycloakTestTokens {

    /** {@code org_id=org-test-1}, realm roles {@code owner}, {@code admin}. */
    public static final String OWNER_USERNAME = "test-owner";

    /** {@code org_id=org-test-2}, realm role {@code viewer}. */
    public static final String VIEWER_USERNAME = "test-viewer";

    /** The shared password for both fixture users. */
    public static final String FIXTURE_PASSWORD = "password";

    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private KeycloakTestTokens() {}

    /**
     * @return a raw JWT access token for {@code username}/{@code password} against the
     *     {@code pallet-test} realm's {@code pallet-test-client}
     */
    public static String passwordGrantToken(String username, String password) {
        String host = KeycloakContainerHolder.CONTAINER.getHost();
        int port = KeycloakContainerHolder.CONTAINER.getMappedPort(KeycloakContainerHolder.HTTP_PORT);
        String tokenEndpoint = "http://%s:%d/realms/%s/protocol/openid-connect/token"
                .formatted(host, port, KeycloakContainerHolder.REALM);

        String form = "grant_type=password&client_id=pallet-test-client&username=%s&password=%s"
                .formatted(username, password);

        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> response;
        try {
            response = CLIENT.send(request, BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to reach Keycloak token endpoint " + tokenEndpoint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Keycloak token endpoint " + tokenEndpoint, e);
        }

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Keycloak token request failed with status " + response.statusCode() + ": " + response.body());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> body = MAPPER.readValue(response.body(), Map.class);
        return (String) body.get("access_token");
    }
}
