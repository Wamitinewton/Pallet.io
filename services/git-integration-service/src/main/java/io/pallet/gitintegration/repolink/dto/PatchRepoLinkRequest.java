package io.pallet.gitintegration.repolink.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Tunes a link without choosing another repository: {@code installationId} and {@code repoId} are unknown fields here.
 * An absent field is unchanged; an empty {@code rootDirectory} builds from the repository root.
 */
public record PatchRepoLinkRequest(
        String productionBranch,
        String rootDirectory,
        Boolean autoDeploy,
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
