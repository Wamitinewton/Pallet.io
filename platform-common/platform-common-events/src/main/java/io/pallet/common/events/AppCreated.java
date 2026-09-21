package io.pallet.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * An app was registered in an organization. Published by {@code org-team-service}; no consumer yet.
 * {@code cloudProvider} is the provider enum name and {@code region} the provider's region id; both
 * are fixed for the life of the app. {@code teamId} is null when the app belongs to no team.
 */
public record AppCreated(
        UUID eventId,
        String eventType,
        String orgId,
        Instant occurredAt,
        String appId,
        String name,
        String slug,
        String teamId,
        String cloudProvider,
        String region,
        String createdByUserId)
        implements PlatformEvent {

    public static final String TYPE = Topics.APP_CREATED;

    public static AppCreated of(
            String orgId,
            String appId,
            String name,
            String slug,
            String teamId,
            String cloudProvider,
            String region,
            String createdByUserId) {
        return new AppCreated(
                UUID.randomUUID(),
                TYPE,
                orgId,
                Instant.now(),
                appId,
                name,
                slug,
                teamId,
                cloudProvider,
                region,
                createdByUserId);
    }
}
