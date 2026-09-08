package io.pallet.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Inbound-webhook fact: {@code git-integration-service} publishes it when a push
 * lands on a connected repository; {@code build-queue-service} consumes it.
 */
public record GitPushReceived(
        UUID eventId, String eventType, String orgId, Instant occurredAt, String repo, String branch, String commitSha)
        implements PlatformEvent {

    public static final String TYPE = Topics.GIT_PUSH_RECEIVED;

    public static GitPushReceived of(String orgId, String repo, String branch, String commitSha) {
        return new GitPushReceived(UUID.randomUUID(), TYPE, orgId, Instant.now(), repo, branch, commitSha);
    }
}
