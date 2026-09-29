package io.pallet.gitintegration.installation.dto;

import java.time.Instant;

/** {@code status} is the installation's on GitHub: {@code ACTIVE} or {@code SUSPENDED}. */
public record InstallationLinkResponse(
        long installationId,
        String accountLogin,
        String accountType,
        String status,
        String linkedByUserId,
        Instant linkedAt) {}
