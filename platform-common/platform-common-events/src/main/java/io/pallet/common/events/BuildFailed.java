package io.pallet.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A build of one app at one commit failed. Published by {@code build-service}; see {@link BuildStarted}.
 * {@code reason} is a short, human-readable cause, never a build log.
 */
public record BuildFailed(
        UUID eventId,
        String eventType,
        String orgId,
        Instant occurredAt,
        String buildId,
        String appId,
        String commitSha,
        UUID pushEventId,
        String reason)
        implements PlatformEvent {

    public static final String TYPE = Topics.BUILD_FAILED;

    public static BuildFailed of(
            String orgId, String buildId, String appId, String commitSha, UUID pushEventId, String reason) {
        return new BuildFailed(
                UUID.randomUUID(), TYPE, orgId, Instant.now(), buildId, appId, commitSha, pushEventId, reason);
    }
}
