package io.pallet.gitintegration.repolink.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.pallet.gitintegration.docs.ApiDocs;
import io.swagger.v3.oas.annotations.media.Schema;

/** {@code branch} defaults to the link's production branch. A commit can't be named: the branch's head is built. */
@Schema(description = "Optional. An empty or absent body builds the production branch's head.")
public record ManualBuildRequestDto(
        @Schema(
                description = "Defaults to the production branch. " + ApiDocs.BRANCH_RULES,
                pattern = ApiDocs.BRANCH_PATTERN,
                example = "main",
                nullable = true)
        String branch) {

    @JsonAnySetter
    @SuppressWarnings("unused")
    void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unknown field: " + field);
    }
}
