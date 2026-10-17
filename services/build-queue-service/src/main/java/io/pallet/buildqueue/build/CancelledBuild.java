package io.pallet.buildqueue.build;

import java.util.UUID;

/** A waiting build taken out of the queue, read back from the cancellation itself. */
public record CancelledBuild(
        UUID buildId, String orgId, String appId, String commitSha, UUID pushEventId, String reason) {}
