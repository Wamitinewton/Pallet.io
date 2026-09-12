package io.pallet.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A user changed their own display name via the self-service {@code PATCH /users/me} endpoint.
 * {@code org-team-service} consumes this to keep its {@code memberships.display_name} projection
 * fresh (see {@code docs/identity-service/ARCHITECTURE.md} §Event contracts). {@code userId} is
 * the account's Keycloak user id, the same identifier every other event crossing this boundary
 * uses.
 */
public record UserProfileUpdated(
        UUID eventId,
        String eventType,
        String orgId,
        Instant occurredAt,
        String userId,
        String email,
        String displayName)
        implements PlatformEvent {

    public static final String TYPE = Topics.USER_PROFILE_UPDATED;

    public static UserProfileUpdated of(String orgId, String userId, String email, String displayName) {
        return new UserProfileUpdated(UUID.randomUUID(), TYPE, orgId, Instant.now(), userId, email, displayName);
    }
}
