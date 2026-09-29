package io.pallet.gitintegration.github;

import java.time.Instant;
import org.jspecify.annotations.NonNull;

record AppJwt(String value, Instant expiresAt) {

    @Override
    public @NonNull String toString() {
        return "AppJwt[value=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
