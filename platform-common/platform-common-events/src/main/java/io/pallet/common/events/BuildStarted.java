package io.pallet.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A build of one app at one commit started. Published by {@code build-service}. {@code pushEventId} is the
 * {@link GitPushReceived#eventId()} that asked for the build. The minimal contract {@code git-integration-service}
 * consumes; {@code build-service} extends it additively.
 */
public record BuildStarted(
        UUID eventId,
        String eventType,
        String orgId,
        Instant occurredAt,
        String buildId,
        String appId,
        String commitSha,
        UUID pushEventId)
        implements PlatformEvent {

    public static final String TYPE = Topics.BUILD_STARTED;

    public static BuildStarted of(String orgId, String buildId, String appId, String commitSha, UUID pushEventId) {
        return new BuildStarted(UUID.randomUUID(), TYPE, orgId, Instant.now(), buildId, appId, commitSha, pushEventId);
    }
}
