package io.pallet.gitintegration.repolink.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/** {@code branch} defaults to the link's production branch. A commit can't be named: the branch's head is built. */
public record ManualBuildRequestDto(String branch) {

    @JsonAnySetter
    @SuppressWarnings("unused")
    void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unknown field: " + field);
    }
}
