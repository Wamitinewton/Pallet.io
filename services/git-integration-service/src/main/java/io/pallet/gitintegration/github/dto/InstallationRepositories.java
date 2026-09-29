package io.pallet.gitintegration.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * One page of {@code GET /installation/repositories} or {@code GET /user/installations/{id}/repositories}. Only the
 * second carries {@code permissions}: the calling user's own on each repository.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstallationRepositories(
        @JsonProperty("total_count") int totalCount, List<Repository> repositories) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(
            long id,
            @JsonProperty("full_name") String fullName,
            @JsonProperty("default_branch") String defaultBranch,
            @JsonProperty("private") boolean isPrivate,
            boolean archived,
            Permissions permissions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Permissions(boolean admin, boolean maintain, boolean push, boolean triage, boolean pull) {}
}
