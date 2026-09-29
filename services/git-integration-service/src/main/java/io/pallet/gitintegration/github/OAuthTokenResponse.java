package io.pallet.gitintegration.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.NonNull;

/**
 * GitHub's answer to a code exchange. {@code refresh_token} and {@code refresh_token_expires_in} are deliberately not
 * declared, so no refresh token is ever bound into a Pallet object.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record OAuthTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") Long expiresIn,
        @JsonProperty("error") String error) {

    @Override
    public @NonNull String toString() {
        return "OAuthTokenResponse[accessToken=<redacted>, tokenType=%s, expiresIn=%s, error=%s]"
                .formatted(tokenType, expiresIn, error);
    }
}
