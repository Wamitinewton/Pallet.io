package io.pallet.buildqueue.build;

import java.util.UUID;

/** A build the queue stopped waiting for, read back from the expiry itself; {@code from} is the status it left. */
public record ExpiredBuild(
        UUID buildId,
        String orgId,
        String appId,
        String commitSha,
        UUID pushEventId,
        BuildStatus from,
        String reason,
        String traceparent) {}
