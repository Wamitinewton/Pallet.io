package io.pallet.gitintegration.session.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** {@code code} and {@code state} from GitHub's redirect; their format is checked where they are used. */
@Schema(description = "code and state from GitHub's redirect; each works once and neither is ever returned.")
public record CompleteAuthorizationRequest(
        @Schema(description = "code from GitHub's redirect.") @NotBlank String code,

        @Schema(description = "state from GitHub's redirect.") @NotBlank String state) {

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
