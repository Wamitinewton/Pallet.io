package io.pallet.identity.auth;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Canonical field names are camelCase, matching every other Pallet API response — {@link JsonAlias}
 * (read-only) is what lets this same record deserialize Keycloak's snake_case token endpoint
 * response without that shape leaking into what this service hands back to its own callers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TokenResponse(
        @JsonAlias("access_token") String accessToken,
        @JsonAlias("refresh_token") String refreshToken,
        @JsonAlias("expires_in") long expiresIn,
        @JsonAlias("refresh_expires_in") long refreshExpiresIn,
        @JsonAlias("token_type") String tokenType) {}
