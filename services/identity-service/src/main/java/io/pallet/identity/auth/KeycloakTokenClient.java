package io.pallet.identity.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.pallet.common.error.AppException;
import io.pallet.common.resilience.ExternalCall;
import io.pallet.identity.config.IdentityServiceProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proxies Keycloak's token endpoint (password/refresh grants) and its RP-initiated logout
 * endpoint. Separate from the {@code Keycloak} Admin Client bean — the token endpoint isn't part
 * of that library's Admin API surface, so this talks to it directly over HTTP.
 *
 * <p>Every call classifies Keycloak's response before it reaches {@link ExternalCall}'s
 * retry/circuit-breaker logic: a successful HTTP exchange carrying {@code invalid_grant} is a
 * business outcome (bad credentials or an expired/revoked token), never a call failure worth
 * retrying. Only a genuine infra failure (connection refused, timeout, 5xx) is left for
 * {@code ExternalCall}'s normal resilience path.
 *
 * <p>Public because {@code account.UserService} also reuses {@link #passwordGrant} to re-verify a
 * caller's current password — Keycloak's Admin API has no way to check a password without
 * changing it, so a throwaway password grant is the only verification path available.
 */
@Component
public class KeycloakTokenClient {

    private static final String KEYCLOAK_TOKEN_POLICY = "keycloak-token";
    private static final String ACCOUNT_NOT_FULLY_SET_UP = "Account is not fully set up";
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final IdentityServiceProperties properties;
    private final ExternalCall externalCall;
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;

    KeycloakTokenClient(IdentityServiceProperties properties, ExternalCall externalCall) {
        this.properties = properties;
        this.externalCall = externalCall;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(HTTP_CONNECT_TIMEOUT).build();
        this.jsonMapper = JsonMapper.builder().build();
    }

    public TokenResponse passwordGrant(String email, String password) {
        return exchange("grant_type=password&client_id=%s&username=%s&password=%s"
                .formatted(clientId(), encode(email), encode(password)));
    }

    TokenResponse refreshGrant(String refreshToken) {
        return exchange(
                "grant_type=refresh_token&client_id=%s&refresh_token=%s".formatted(clientId(), encode(refreshToken)));
    }

    void revokeSession(String refreshToken) {
        externalCall.run(KEYCLOAK_TOKEN_POLICY, () -> {
            HttpResponse<String> response =
                    post(logoutEndpoint(), "client_id=%s&refresh_token=%s".formatted(clientId(), encode(refreshToken)));
            if (!isSuccess(response.statusCode())) {
                throw new InvalidCredentialsException("Keycloak rejected the logout: " + response.statusCode());
            }
        });
    }

    private TokenResponse exchange(String form) {
        return externalCall.call(KEYCLOAK_TOKEN_POLICY, () -> {
            HttpResponse<String> response = post(tokenEndpoint(), form);
            if (response.statusCode() == 200) {
                return jsonMapper.readValue(response.body(), TokenResponse.class);
            }
            throw classify(response.body());
        });
    }

    private AppException classify(String errorBody) {
        String description = errorDescription(errorBody);
        if (description != null && description.contains(ACCOUNT_NOT_FULLY_SET_UP)) {
            return new EmailNotVerifiedException();
        }
        return new InvalidCredentialsException("Keycloak rejected the grant: " + description);
    }

    private String errorDescription(String body) {
        try {
            return jsonMapper.readValue(body, KeycloakErrorResponse.class).errorDescription();
        } catch (RuntimeException malformedBody) {
            return null;
        }
    }

    private HttpResponse<String> post(String uri, String form) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to reach Keycloak token endpoint " + uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Keycloak token endpoint " + uri, e);
        }
    }

    private String tokenEndpoint() {
        return properties.keycloak().serverUrl() + "/realms/"
                + properties.keycloak().realm() + "/protocol/openid-connect/token";
    }

    private String logoutEndpoint() {
        return properties.keycloak().serverUrl() + "/realms/"
                + properties.keycloak().realm() + "/protocol/openid-connect/logout";
    }

    private String clientId() {
        return properties.keycloak().tokenClientId();
    }

    private static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record KeycloakErrorResponse(
            String error, @JsonProperty("error_description") String errorDescription) {}
}
