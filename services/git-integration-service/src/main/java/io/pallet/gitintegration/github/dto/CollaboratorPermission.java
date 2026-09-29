package io.pallet.gitintegration.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code GET /repositories/{id}/collaborators/{username}/permission}. {@code permission} is GitHub's legacy base role
 * ({@code admin}, {@code write}, {@code read}, {@code none}); {@code roleName} is the assigned role, which may be a
 * custom one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CollaboratorPermission(
        String permission, @JsonProperty("role_name") String roleName, GitHubUser user) {}
