package io.pallet.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A deployment moved between states. Matches the wire sample in {@code PROJECT.md}, plus three additive fields that
 * {@code git-integration-service} needs to report the outcome against a commit: {@code appId}, {@code commitSha}, and
 * {@code url}, the live URL once there is one. All three are null in a payload from before they existed.
 */
public record DeployStateChanged(
        UUID eventId,
        String eventType,
        String orgId,
        Instant occurredAt,
        String deploymentId,
        String fromState,
        String toState,
        String appId,
        String commitSha,
        String url)
        implements PlatformEvent {

    public static final String TYPE = Topics.DEPLOY_STATE_CHANGED;

    public static DeployStateChanged of(String orgId, String deploymentId, String fromState, String toState) {
        return of(orgId, deploymentId, fromState, toState, null, null, null);
    }

    public static DeployStateChanged of(
            String orgId,
            String deploymentId,
            String fromState,
            String toState,
            String appId,
            String commitSha,
            String url) {
        return new DeployStateChanged(
                UUID.randomUUID(), TYPE, orgId, Instant.now(), deploymentId, fromState, toState, appId, commitSha, url);
    }
}
