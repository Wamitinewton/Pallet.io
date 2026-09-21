package io.pallet.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A member joined an organization, either as the founding owner or by accepting an invite.
 * Published by {@code org-team-service}; consumed by {@code notification-service}. {@code userId} is
 * the member's Keycloak user id.
 */
public record OrgMemberAdded(
        UUID eventId, String eventType, String orgId, Instant occurredAt, String userId, String email)
        implements PlatformEvent {

    public static final String TYPE = Topics.ORG_MEMBER_ADDED;

    public static OrgMemberAdded of(String orgId, String userId, String email) {
        return new OrgMemberAdded(UUID.randomUUID(), TYPE, orgId, Instant.now(), userId, email);
    }
}
