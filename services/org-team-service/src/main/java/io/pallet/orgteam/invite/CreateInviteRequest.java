package io.pallet.orgteam.invite;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.pallet.orgteam.member.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateInviteRequest(
        @Schema(description = "Address the invite link is sent to", example = "dev@example.com", maxLength = 255)
        @NotBlank @Email @Size(max = 255) String email,

        @Schema(
                description = "Role granted on acceptance; OWNER cannot be invited",
                allowableValues = {"ADMIN", "DEVELOPER", "VIEWER"},
                example = "DEVELOPER")
        @NotNull Role role) {

    @JsonAnySetter
    @SuppressWarnings("unused")
    void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unknown field: " + field);
    }
}
