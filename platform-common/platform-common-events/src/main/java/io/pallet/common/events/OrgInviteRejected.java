package io.pallet.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * An accepted invite could not be honoured. Published by {@code org-team-service}; consumed by
 * {@code identity-service}, which disables the account it created for the invite. {@code inviteId}
 * is the invite's id, the same value {@code OrgInviteAccepted.inviteId} carries. {@code reason} is
 * one of the {@code REASON_*} constants.
 */
public record OrgInviteRejected(
        UUID eventId,
        String eventType,
        String orgId,
        Instant occurredAt,
        String inviteId,
        String userId,
        String email,
        String reason)
        implements PlatformEvent {

    public static final String TYPE = Topics.ORG_INVITE_REJECTED;

    public static final String REASON_REVOKED = "REVOKED";
    public static final String REASON_EXPIRED = "EXPIRED";
    public static final String REASON_UNKNOWN_INVITE = "UNKNOWN_INVITE";
    public static final String REASON_ORG_DELETED = "ORG_DELETED";
    public static final String REASON_ALREADY_ACCEPTED = "ALREADY_ACCEPTED";

    public static OrgInviteRejected of(String orgId, String inviteId, String userId, String email, String reason) {
        return new OrgInviteRejected(UUID.randomUUID(), TYPE, orgId, Instant.now(), inviteId, userId, email, reason);
    }
}
