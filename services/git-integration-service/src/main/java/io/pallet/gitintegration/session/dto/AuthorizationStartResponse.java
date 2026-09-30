package io.pallet.gitintegration.session.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "The state inside authorizeUrl is the only one ever returned; it works once, for this caller.")
public record AuthorizationStartResponse(
        @Schema(example = "https://github.com/login/oauth/authorize?client_id=example-client&state=example-state")
        String authorizeUrl,

        @Schema(description = "When the state in authorizeUrl expires.")
        Instant expiresAt) {}
