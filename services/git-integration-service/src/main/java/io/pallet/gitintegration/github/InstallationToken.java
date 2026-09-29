package io.pallet.gitintegration.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import org.jspecify.annotations.NonNull;

/** An installation access token. Held in memory only; never persisted, logged, or put in an event. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstallationToken(
        @JsonProperty("token") String value,
        @JsonProperty("expires_at") Instant expiresAt) {

    @Override
    public @NonNull String toString() {
        return "InstallationToken[value=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
