package io.pallet.gitintegration.installation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** {@code status} is the installation's on GitHub: {@code ACTIVE} or {@code SUSPENDED}. */
public record InstallationLinkResponse(
        @Schema(example = "41000001") long installationId,
        @Schema(example = "example-org") String accountLogin,
        @Schema(allowableValues = {"User", "Organization"}) String accountType,
        @Schema(allowableValues = {"ACTIVE", "SUSPENDED"}) String status,
        String linkedByUserId,
        Instant linkedAt) {}
