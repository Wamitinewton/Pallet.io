package io.pallet.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A build entered the queue. Published by {@code build-queue-service}; no consumer yet. {@code supersedes}
 * is the {@code buildId} of the waiting build this one replaced, or null.
 */
public record BuildQueued(
        UUID eventId,
        String eventType,
        String orgId,
        Instant occurredAt,
        String buildId,
        String appId,
        String branch,
        String commitSha,
        UUID pushEventId,
        String trigger,
        String supersedes)
        implements PlatformEvent {

    public static final String TYPE = Topics.BUILD_QUEUED;

    /**
     * Takes {@code eventId} and {@code occurredAt} from the caller because the producer derives the id
     * deterministically from the {@code buildId}.
     */
    public static BuildQueued of(
            UUID eventId,
            String orgId,
            Instant occurredAt,
            String buildId,
            String appId,
            String branch,
            String commitSha,
            UUID pushEventId,
            String trigger,
            String supersedes) {
        return new BuildQueued(
                eventId, TYPE, orgId, occurredAt, buildId, appId, branch, commitSha, pushEventId, trigger, supersedes);
    }
}
