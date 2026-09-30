package io.pallet.gitintegration.repolink.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.pallet.gitintegration.docs.ApiDocs;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Tunes a link without choosing another repository: {@code installationId} and {@code repoId} are unknown fields here.
 * An absent field is unchanged; an empty {@code rootDirectory} builds from the repository root.
 */
@Schema(
        description = "Tunes a link without choosing another repository. At least one of productionBranch, "
                + "rootDirectory and autoDeploy is required; any other field, installationId and repoId included, is "
                + "a 400.")
public record PatchRepoLinkRequest(
        @Schema(
                description = "Must exist on GitHub. " + ApiDocs.BRANCH_RULES,
                pattern = ApiDocs.BRANCH_PATTERN,
                example = "release",
                nullable = true)
        String productionBranch,

        @Schema(
                description = ApiDocs.ROOT_DIRECTORY_RULES,
                pattern = ApiDocs.ROOT_DIRECTORY_PATTERN,
                example = "services/api",
                nullable = true)
        String rootDirectory,

        @Schema(description = "Whether future pushes to the production branch build.", nullable = true)
        Boolean autoDeploy,

        @Schema(description = "The link's version from the last read; a stale one is 409.", example = "3")
        @NotNull @PositiveOrZero Long version) {

    @JsonAnySetter
    @SuppressWarnings("unused")
    void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unknown field: " + field);
    }

    @JsonIgnore
    @AssertTrue(message = "at least one of productionBranch, rootDirectory, autoDeploy is required") public boolean isChangingSomething() {
        return productionBranch != null || rootDirectory != null || autoDeploy != null;
    }
}
