package io.pallet.gitintegration.installation.dto;

import java.time.Instant;

public record InstallSessionResponse(String installUrl, Instant expiresAt) {

    @Override
    public String toString() {
        return "InstallSessionResponse[installUrl=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
