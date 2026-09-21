package io.pallet.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * An app was deleted, directly or because its organization was. Published by
 * {@code org-team-service}; no consumer yet.
 */
public record AppDeleted(
        UUID eventId,
        String eventType,
        String orgId,
        Instant occurredAt,
        String appId,
        String slug,
        String deletedByUserId)
        implements PlatformEvent {

    public static final String TYPE = Topics.APP_DELETED;

    public static AppDeleted of(String orgId, String appId, String slug, String deletedByUserId) {
        return new AppDeleted(UUID.randomUUID(), TYPE, orgId, Instant.now(), appId, slug, deletedByUserId);
    }
}
