package io.pallet.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The full current state of one membership. Published by {@code org-team-service} to the compacted
 * {@code org.membership.changed} topic under {@link #key(String, String)}; every tenant-scoped service
 * seeds and maintains its membership read model from it (ADR-0019). A consumer applies a record only
 * when {@code membershipVersion} is greater than the version it already holds. {@code role} is the
 * lowercase Keycloak role name.
 */
public record OrgMembershipChanged(
        UUID eventId,
        String eventType,
        String orgId,
        Instant occurredAt,
        String userId,
        String role,
        String status,
        long membershipVersion)
        implements PlatformEvent {

    public static final String TYPE = Topics.ORG_MEMBERSHIP_CHANGED;
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REMOVED = "REMOVED";

    public static OrgMembershipChanged of(
            String orgId, String userId, String role, String status, long membershipVersion) {
        return new OrgMembershipChanged(
                UUID.randomUUID(), TYPE, orgId, Instant.now(), userId, role, status, membershipVersion);
    }

    /** The record key for one membership on the compacted topic. */
    public static String key(String orgId, String userId) {
        return orgId + ":" + userId;
    }
}
