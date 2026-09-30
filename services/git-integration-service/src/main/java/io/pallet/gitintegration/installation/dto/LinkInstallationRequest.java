package io.pallet.gitintegration.installation.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code {installationId, code, state}} straight from a fresh install's redirect, or {@code {installationId}} for an
 * installation that already exists. {@code installationId} is only a claim until the caller's GitHub user proves it.
 */
@Schema(
        description = "Either all three fields from a fresh install's setup redirect, or installationId alone for an "
                + "installation that already exists. code and state go together or not at all.")
public record LinkInstallationRequest(
        @Schema(
                description = "installation_id from GitHub's setup redirect, or from GET /github/installations.",
                example = "41000001")
        @NotNull @Positive Long installationId,

        @Schema(description = "code from GitHub's setup redirect; used once and never returned.", nullable = true)
        String code,

        @Schema(description = "state from GitHub's setup redirect; used once and never returned.", nullable = true)
        String state) {

    @JsonAnySetter
    @SuppressWarnings("unused")
    void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unknown field: " + field);
    }

    @JsonIgnore
    public boolean isFreshInstall() {
        return code != null || state != null;
    }

    @JsonIgnore
    @AssertTrue(message = "code and state are sent together or not at all") public boolean isCodeWithState() {
        return (code == null) == (state == null);
    }

    @Override
    public String toString() {
        return "LinkInstallationRequest[installationId=" + installationId + ", code=<redacted>, state=<redacted>]";
    }
}
