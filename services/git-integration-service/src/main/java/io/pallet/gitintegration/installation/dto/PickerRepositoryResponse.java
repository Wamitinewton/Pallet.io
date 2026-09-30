package io.pallet.gitintegration.installation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/** {@code permission} is the caller's own on GitHub; {@code linkable} is what the link endpoint would accept. */
public record PickerRepositoryResponse(
        @Schema(example = "72000001") long repoId,
        @Schema(example = "example-org/web") String fullName,
        @Schema(example = "main") String defaultBranch,
        @JsonProperty("private") boolean isPrivate,
        boolean archived,

        @Schema(
                description = "The caller's own highest permission on GitHub, or null for none.",
                nullable = true,
                allowableValues = {"admin", "maintain", "push", "triage", "pull"})
        String permission,

        @Schema(description = "Whether PUT repo-link would accept it: push or above, and not archived.")
        boolean linkable) {}
