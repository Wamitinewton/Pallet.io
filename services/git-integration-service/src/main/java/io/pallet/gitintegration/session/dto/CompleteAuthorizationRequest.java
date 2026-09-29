package io.pallet.gitintegration.session.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;

/** {@code code} and {@code state} from GitHub's redirect; their format is checked where they are used. */
public record CompleteAuthorizationRequest(
        @NotBlank String code, @NotBlank String state) {

    @JsonAnySetter
    @SuppressWarnings("unused")
    void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unknown field: " + field);
    }

    @Override
    public String toString() {
        return "CompleteAuthorizationRequest[code=<redacted>, state=<redacted>]";
    }
}
