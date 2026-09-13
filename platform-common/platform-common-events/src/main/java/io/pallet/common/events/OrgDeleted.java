package io.pallet.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * An organization was deleted. Published by {@code org-team-service}; consumed by
 * {@code identity-service}, which disables every Keycloak account under the org.
 */
public record OrgDeleted(UUID eventId, String eventType, String orgId, Instant occurredAt, String deletedByUserId)
        implements PlatformEvent {

    public static final String TYPE = Topics.ORG_DELETED;

    public static OrgDeleted of(String orgId, String deletedByUserId) {
        return new OrgDeleted(UUID.randomUUID(), TYPE, orgId, Instant.now(), deletedByUserId);
    }
}
