package io.pallet.gitintegration.installation.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code {installationId, code, state}} straight from a fresh install's redirect, or {@code {installationId}} for an
 * installation that already exists. {@code installationId} is only a claim until the caller's GitHub user proves it.
 */
public record LinkInstallationRequest(@NotNull @Positive Long installationId, String code, String state) {

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
