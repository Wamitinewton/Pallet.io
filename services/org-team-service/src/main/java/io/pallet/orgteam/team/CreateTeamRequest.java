package io.pallet.orgteam.team;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.pallet.orgteam.support.Slugs;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateTeamRequest(
        @Schema(description = "Display name", example = "Platform", maxLength = 100) @NotBlank @Size(max = 100) String name,

        @Schema(
                description = "DNS-1123 label; derived from the name when omitted",
                example = "platform",
                maxLength = Slugs.MAX_LENGTH,
                pattern = Slugs.DNS_LABEL_REGEX)
        @Size(max = Slugs.MAX_LENGTH) @Pattern(regexp = Slugs.DNS_LABEL_REGEX, message = "must be a DNS-1123 label") String slug) {

    @JsonAnySetter
    @SuppressWarnings("unused")
    void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unknown field: " + field);
    }
}
