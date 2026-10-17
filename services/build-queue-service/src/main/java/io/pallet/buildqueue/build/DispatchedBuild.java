package io.pallet.buildqueue.build;

import java.time.Instant;
import java.util.UUID;

/**
 * A build as its dispatch left it, read back from the guarded update itself, so the event announcing the dispatch and
 * the stored row can't disagree on {@code attempt} or {@code startDeadline}. {@code dispatchedAt} is the first
 * dispatch's time, whatever the attempt.
 */
public record DispatchedBuild(
        UUID buildId,
        String orgId,
        String appId,
        String branch,
        String commitSha,
        UUID pushEventId,
        String trigger,
        String provider,
        long installationId,
        long repoId,
        String repo,
        String rootDirectory,
        int attempt,
        Instant startDeadline,
        PriorityClass priorityClass,
        Instant enqueuedAt,
        Instant dispatchedAt,
        String traceparent) {}
