package io.pallet.orgteam.app;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public class UpdateAppRequest {

    @Schema(description = "New display name", example = "Storefront API", maxLength = 100)
    @Size(max = 100) @Pattern(regexp = ".*\\S.*", message = "must not be blank") private String name;

    @Schema(description = "New owning team; send null to detach the app from its team")
    private UUID teamId;

    private boolean teamIdPresent;

    public void setName(String name) {
        this.name = name;
    }

    public void setTeamId(UUID teamId) {
        this.teamId = teamId;
        this.teamIdPresent = true;
    }

    public String name() {
        return name;
    }

    public UUID teamId() {
        return teamId;
    }

    public boolean hasTeamId() {
        return teamIdPresent;
    }

    @JsonAnySetter
    @SuppressWarnings("unused")
    void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unknown field: " + field);
    }
}
