package io.pallet.buildqueue.build;

import java.util.UUID;

/**
 * A validated build request, exactly as intake writes it. {@code headCommitMessage} and {@code headCommitAuthor} are
 * untrusted display text: stored, never logged.
 */
public record NewBuild(
        UUID buildId,
        String orgId,
        String appId,
        String branch,
        String commitSha,
        UUID pushEventId,
        String trigger,
        PriorityClass priorityClass,
        String provider,
        long installationId,
        long repoId,
        String repo,
        String rootDirectory,
        String headCommitMessage,
        String headCommitAuthor,
        String traceparent) {}
