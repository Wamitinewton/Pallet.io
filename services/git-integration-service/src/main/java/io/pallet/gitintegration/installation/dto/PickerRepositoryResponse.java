package io.pallet.gitintegration.installation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code permission} is the caller's own on GitHub; {@code linkable} is what the link endpoint would accept. */
public record PickerRepositoryResponse(
        long repoId,
        String fullName,
        String defaultBranch,
        @JsonProperty("private") boolean isPrivate,
        boolean archived,
        String permission,
        boolean linkable) {}
