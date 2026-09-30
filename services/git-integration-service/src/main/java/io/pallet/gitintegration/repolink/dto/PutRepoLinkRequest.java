package io.pallet.gitintegration.repolink.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.pallet.gitintegration.docs.ApiDocs;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code productionBranch} defaults to the repository's default branch, {@code autoDeploy} to true, {@code deployNow}
 * to false. The ids are only claims until the caller's own GitHub token backs them.
 */
@Schema(description = "Chooses the repository an app deploys from. Any field not listed here is a 400.")
public record PutRepoLinkRequest(
        @Schema(description = "An installation linked to this org.", example = "41000001") @NotNull @Positive Long installationId,

        @Schema(description = "GitHub's numeric repository id, from the repository picker.", example = "72000001")
        @NotNull @Positive Long repoId,

        @Schema(
                description = "Defaults to the repository's default branch. " + ApiDocs.BRANCH_RULES,
                pattern = ApiDocs.BRANCH_PATTERN,
                example = "main",
                nullable = true)
        String productionBranch,

        @Schema(
                description = ApiDocs.ROOT_DIRECTORY_RULES,
                pattern = ApiDocs.ROOT_DIRECTORY_PATTERN,
                example = "apps/web",
                nullable = true)
        String rootDirectory,

        @Schema(description = "Whether future pushes to the production branch build.", defaultValue = "true")
        Boolean autoDeploy,

        @Schema(description = "Whether to build the branch's current head right away.", defaultValue = "false")
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
