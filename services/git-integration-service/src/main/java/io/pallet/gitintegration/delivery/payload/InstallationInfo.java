package io.pallet.gitintegration.delivery.payload;

import java.util.Map;

/** {@code repositorySelection} is {@code all} or {@code selected}; {@code permissions} maps a permission to its level. */
public record InstallationInfo(
        long id,
        GitHubAccount account,
        String repositorySelection,
        Map<String, String> permissions,
        boolean suspended) {

    public InstallationInfo {
        permissions = Map.copyOf(permissions);
    }
}
