package io.pallet.common.events;

import java.time.Instant;
import java.util.UUID;

/** A build of one app at one commit produced an image. Published by {@code build-service}; see {@link BuildStarted}. */
public record BuildSucceeded(
        UUID eventId,
        String eventType,
        String orgId,
        Instant occurredAt,
        String buildId,
        String appId,
        String commitSha,
        UUID pushEventId)
        implements PlatformEvent {

    public static final String TYPE = Topics.BUILD_SUCCEEDED;

    public static BuildSucceeded of(String orgId, String buildId, String appId, String commitSha, UUID pushEventId) {
        return new BuildSucceeded(
                UUID.randomUUID(), TYPE, orgId, Instant.now(), buildId, appId, commitSha, pushEventId);
    }
}
