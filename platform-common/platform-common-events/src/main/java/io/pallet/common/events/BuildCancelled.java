package io.pallet.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A build left the queue without a result from {@code build-service}. Published by {@code build-queue-service};
 * no consumer yet. {@code supersededBy} is the {@code buildId} that replaced it when {@code reason} is
 * {@link #REASON_SUPERSEDED}, otherwise null.
 *
 * <p>{@link #REASON_START_TIMEOUT} and {@link #REASON_LEASE_EXPIRED} mean the queue gave up waiting, not that
 * the build was stopped; a result may still arrive later.
 */
public record BuildCancelled(
        UUID eventId,
        String eventType,
        String orgId,
        Instant occurredAt,
        String buildId,
        String appId,
        String commitSha,
        UUID pushEventId,
        String reason,
        String supersededBy)
        implements PlatformEvent {

    public static final String TYPE = Topics.BUILD_CANCELLED;
    public static final String REASON_SUPERSEDED = "SUPERSEDED";
    public static final String REASON_USER_CANCELLED = "USER_CANCELLED";
    public static final String REASON_APP_DELETED = "APP_DELETED";
    public static final String REASON_ORG_DELETED = "ORG_DELETED";
    public static final String REASON_QUEUE_FULL = "QUEUE_FULL";
    public static final String REASON_QUEUE_TIMEOUT = "QUEUE_TIMEOUT";
    public static final String REASON_START_TIMEOUT = "START_TIMEOUT";
    public static final String REASON_LEASE_EXPIRED = "LEASE_EXPIRED";

    /**
     * Takes {@code eventId} and {@code occurredAt} from the caller because the producer derives the id
     * deterministically from the {@code buildId}.
     */
    public static BuildCancelled of(
            UUID eventId,
            String orgId,
            Instant occurredAt,
            String buildId,
            String appId,
            String commitSha,
            UUID pushEventId,
            String reason,
            String supersededBy) {
        return new BuildCancelled(
                eventId, TYPE, orgId, occurredAt, buildId, appId, commitSha, pushEventId, reason, supersededBy);
    }
}
