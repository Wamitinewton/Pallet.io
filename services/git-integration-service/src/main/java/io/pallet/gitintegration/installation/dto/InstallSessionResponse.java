package io.pallet.gitintegration.installation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(
        description =
                "The state inside installUrl is the only one ever returned; it works once, for this org and caller.")
public record InstallSessionResponse(
        @Schema(example = "https://github.com/apps/pallet/installations/new?state=example-state")
        String installUrl,

        @Schema(description = "When the state in installUrl expires.")
        Instant expiresAt) {

    @Override
    public String toString() {
        return "InstallSessionResponse[installUrl=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
