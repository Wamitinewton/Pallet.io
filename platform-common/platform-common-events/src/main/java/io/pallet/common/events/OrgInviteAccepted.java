package io.pallet.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * An invited user completed account creation by presenting a signed invite token to
 * {@code identity-service}. {@code org-team-service} consumes this to mark its own invite record
 * accepted and publish {@code OrgMemberAdded} — the same one-producer-per-fact split
 * {@code OrgProvisioned} follows (see {@code docs/identity-service/ARCHITECTURE.md} §Event
 * contracts). {@code inviteId} is the accepted token's own {@code jti}, since that value already
 * uniquely identifies the invite on both sides of the handoff. {@code userId} is the new account's
 * Keycloak user id.
 */
public record OrgInviteAccepted(
        UUID eventId,
        String eventType,
        String orgId,
        Instant occurredAt,
        String inviteId,
        String userId,
        String email,
        String displayName,
        String role)
        implements PlatformEvent {

    public static final String TYPE = Topics.ORG_INVITE_ACCEPTED;

    public static OrgInviteAccepted of(
            String orgId, String inviteId, String userId, String email, String displayName, String role) {
        return new OrgInviteAccepted(
                UUID.randomUUID(), TYPE, orgId, Instant.now(), inviteId, userId, email, displayName, role);
    }
}
