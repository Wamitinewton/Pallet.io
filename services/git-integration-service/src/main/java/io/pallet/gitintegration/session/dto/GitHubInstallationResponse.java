package io.pallet.gitintegration.session.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record GitHubInstallationResponse(
        @Schema(example = "41000001") long installationId,
        @Schema(example = "example-org") String accountLogin,
        @Schema(allowableValues = {"User", "Organization"}) String accountType,

        @Schema(description = "Suspended on GitHub; linking it is refused until it is unsuspended.")
        boolean suspended) {}
