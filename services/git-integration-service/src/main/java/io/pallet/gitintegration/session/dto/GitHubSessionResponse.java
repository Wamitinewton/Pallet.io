package io.pallet.gitintegration.session.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(
        description = "The caller's GitHub session. Never returned: the GitHub token, a code, or a state; the only "
                + "state ever returned is the one inside authorizeUrl or installUrl.")
public record GitHubSessionResponse(
        @Schema(example = "octo-example") String githubLogin,

        @Schema(description = "After this the session is gone and GitHub authorization starts again.")
        Instant expiresAt) {}
