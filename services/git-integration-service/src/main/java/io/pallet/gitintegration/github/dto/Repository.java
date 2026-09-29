package io.pallet.gitintegration.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code GET /repositories/{id}} with a user token; {@code permissions} are that user's own. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Repository(
        long id,
        @JsonProperty("full_name") String fullName,
        @JsonProperty("default_branch") String defaultBranch,
        boolean archived,
        Owner owner,
        InstallationRepositories.Permissions permissions) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Owner(long id) {}
}
