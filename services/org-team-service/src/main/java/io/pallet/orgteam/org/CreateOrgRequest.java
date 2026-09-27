package io.pallet.orgteam.org;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.pallet.orgteam.support.Slugs;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateOrgRequest(
        @Schema(description = "Display name", example = "Acme Inc", maxLength = 255)
        @NotBlank @Size(max = 255) @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters") String name,

        @Schema(
                description = "DNS-1123 label, unique across every organization; derived from the name when omitted",
                example = "acme-inc",
                maxLength = Slugs.MAX_LENGTH,
                pattern = Slugs.DNS_LABEL_REGEX)
        @Size(max = Slugs.MAX_LENGTH) @Pattern(regexp = Slugs.DNS_LABEL_REGEX, message = "must be a DNS-1123 label") String slug) {

    public CreateOrgRequest {
        name = name == null ? null : name.strip();
    }

    @JsonAnySetter
    @SuppressWarnings("unused")
    void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unknown field: " + field);
    }
}
