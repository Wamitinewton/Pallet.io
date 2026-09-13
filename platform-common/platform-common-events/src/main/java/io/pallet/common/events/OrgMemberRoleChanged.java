package io.pallet.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A member's role changed within an organization. Published by {@code org-team-service}; consumed
 * by {@code identity-service}, which replaces the member's Keycloak realm-role assignment.
 * {@code userId} is the member's Keycloak user id.
 */
public record OrgMemberRoleChanged(
        UUID eventId,
        String eventType,
        String orgId,
        Instant occurredAt,
        String userId,
        String previousRole,
        String newRole)
        implements PlatformEvent {

    public static final String TYPE = Topics.ORG_MEMBER_ROLE_CHANGED;

    public static OrgMemberRoleChanged of(String orgId, String userId, String previousRole, String newRole) {
        return new OrgMemberRoleChanged(UUID.randomUUID(), TYPE, orgId, Instant.now(), userId, previousRole, newRole);
    }
}
