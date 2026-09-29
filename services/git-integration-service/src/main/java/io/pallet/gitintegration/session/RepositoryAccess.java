package io.pallet.gitintegration.session;

/**
 * A repository as one user's token sees it. {@code permission} is their highest: {@code admin}, {@code maintain},
 * {@code push}, {@code triage}, {@code pull}, or null.
 */
public record RepositoryAccess(
        long id, long ownerId, String fullName, String defaultBranch, boolean archived, String permission) {}
