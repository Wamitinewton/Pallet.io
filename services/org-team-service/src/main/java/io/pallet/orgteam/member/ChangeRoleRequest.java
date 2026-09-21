package io.pallet.orgteam.member;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record ChangeRoleRequest(
        @Schema(
                description = "New role; OWNER is assigned only by transferring ownership",
                allowableValues = {"ADMIN", "DEVELOPER", "VIEWER"},
                example = "ADMIN")
        @NotNull Role role) {

    @JsonAnySetter
    @SuppressWarnings("unused")
    void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unknown field: " + field);
    }
}
