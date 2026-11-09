package io.pallet.build.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A validated {@code build.dispatched}: everything the build needs to run and nothing secret. {@code rootDirectory} is
 * {@code ""} for the repository root; {@code traceparent} is null when the record carried no usable one.
 */
public record NewBuild(
        String buildId,
        String orgId,
        String appId,
        int attempt,
        String branch,
        String commitSha,
        UUID pushEventId,
        String trigger,
        String provider,
        long installationId,
        long repoId,
        String repo,
        String rootDirectory,
        Instant startBy,
        long maxDurationSeconds,
        String traceparent) {}
