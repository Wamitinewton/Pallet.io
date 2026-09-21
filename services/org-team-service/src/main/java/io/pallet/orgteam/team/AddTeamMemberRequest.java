package io.pallet.orgteam.team;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddTeamMemberRequest(
        @Schema(
                description = "Id of an active member of the organization",
                example = "4d1c9a52-7f3e-4a86-9c0b-2e5f8a1b3c47",
                maxLength = 64)
        @NotBlank @Size(max = 64) String userId) {

    @JsonAnySetter
    @SuppressWarnings("unused")
    void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unknown field: " + field);
    }
}
