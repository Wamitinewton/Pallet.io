package io.pallet.gitintegration.repolink.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code lastAcceptedHead} is the production branch's head as push processing last accepted it, or null before the
 * first. {@code warnings} are codes for an active link that still works but needs attention, such as
 * {@link #REPOSITORY_ARCHIVED}. {@code version} is what a {@code PATCH} must send back.
 */
public record RepoLinkDto(
        UUID appId,
        long installationId,
        long repoId,
        String repoFullName,
        String productionBranch,
        String rootDirectory,
        boolean autoDeploy,
        String status,
        String disconnectReason,
        Instant disconnectedAt,
        List<String> warnings,
        Head lastAcceptedHead,
        Verification verification,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static final String REPOSITORY_ARCHIVED = "REPOSITORY_ARCHIVED";

    public RepoLinkDto {
        warnings = List.copyOf(warnings);
    }

    public record Head(String branch, String sha, Instant advancedAt) {}

    /** Who vouched for the link on GitHub, and when that was last confirmed. */
    public record Verification(
            String verifiedByUserId,
            String githubLogin,
            String permission,
            Instant accessVerifiedAt,
            Instant accessCheckedAt) {}
}
