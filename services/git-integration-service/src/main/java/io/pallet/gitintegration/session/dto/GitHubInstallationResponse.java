package io.pallet.gitintegration.session.dto;

public record GitHubInstallationResponse(
        long installationId, String accountLogin, String accountType, boolean suspended) {}
