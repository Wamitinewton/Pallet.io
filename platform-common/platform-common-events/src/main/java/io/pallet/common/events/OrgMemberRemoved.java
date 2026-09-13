package io.pallet.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A member was removed from an organization. Published by {@code org-team-service}; consumed by
 * {@code identity-service}, which disables the corresponding Keycloak account, and by
 * {@code notification-service}. {@code userId} is the member's Keycloak user id, the same
 * identifier {@code identity-service} handed {@code org-team-service} in {@code OrgProvisioned}/
 * {@code OrgInviteAccepted}.
 */
public record OrgMemberRemoved(
        UUID eventId, String eventType, String orgId, Instant occurredAt, String userId, String email)
        implements PlatformEvent {

    public static final String TYPE = Topics.ORG_MEMBER_REMOVED;

    public static OrgMemberRemoved of(String orgId, String userId, String email) {
        return new OrgMemberRemoved(UUID.randomUUID(), TYPE, orgId, Instant.now(), userId, email);
    }
}
