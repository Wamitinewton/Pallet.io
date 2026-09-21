package io.pallet.orgteam.app;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.pallet.orgteam.support.Slugs;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateAppRequest(
        @Schema(description = "Display name", example = "Storefront API", maxLength = 100) @NotBlank @Size(max = 100) String name,

        @Schema(
                description = "DNS-1123 label; derived from the name when omitted",
                example = "storefront-api",
                maxLength = Slugs.MAX_LENGTH,
                pattern = Slugs.DNS_LABEL_REGEX)
        @Size(max = Slugs.MAX_LENGTH) @Pattern(regexp = Slugs.DNS_LABEL_REGEX, message = "must be a DNS-1123 label") String slug,

        @Schema(description = "Where the app runs. Fixed once the app is created.", example = "AWS") @NotNull CloudProvider cloudProvider,

        @Schema(
                description = "A region on the provider's allow-list. Fixed once the app is created.",
                example = "eu-west-1",
                maxLength = 32)
        @NotBlank @Size(max = 32) String region,

        @Schema(description = "Team that owns the app; optional")
        UUID teamId) {

    @JsonAnySetter
    @SuppressWarnings("unused")
    void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unknown field: " + field);
    }
}
