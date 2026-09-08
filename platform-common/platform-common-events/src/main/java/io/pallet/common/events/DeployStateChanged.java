package io.pallet.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Reference event contract, matching the wire sample in {@code PROJECT.md}.
 *
 * <p>New events follow this shape: a flat record implementing
 * {@link PlatformEvent}, a {@code TYPE} constant pointing at its {@link Topics}
 * entry, and a static factory that stamps {@code eventId}, {@code eventType}
 * and {@code occurredAt} so callers only supply the domain fields.
 */
public record DeployStateChanged(
        UUID eventId,
        String eventType,
        String orgId,
        Instant occurredAt,
        String deploymentId,
        String fromState,
        String toState)
        implements PlatformEvent {

    public static final String TYPE = Topics.DEPLOY_STATE_CHANGED;

    public static DeployStateChanged of(String orgId, String deploymentId, String fromState, String toState) {
        return new DeployStateChanged(UUID.randomUUID(), TYPE, orgId, Instant.now(), deploymentId, fromState, toState);
    }
}
