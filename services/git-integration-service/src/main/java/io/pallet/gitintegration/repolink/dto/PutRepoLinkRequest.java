package io.pallet.gitintegration.repolink.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code productionBranch} defaults to the repository's default branch, {@code autoDeploy} to true, {@code deployNow}
 * to false. The ids are only claims until the caller's own GitHub token backs them.
 */
public record PutRepoLinkRequest(
        @NotNull @Positive Long installationId,
        @NotNull @Positive Long repoId,
        String productionBranch,
        String rootDirectory,
        Boolean autoDeploy,
        Boolean deployNow) {

    @JsonAnySetter
    @SuppressWarnings("unused")
    void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unknown field: " + field);
    }

    public boolean autoDeployOrDefault() {
        return autoDeploy == null || autoDeploy;
    }

    public boolean deployNowOrDefault() {
        return deployNow != null && deployNow;
    }
}
