package io.pallet.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A brand-new organization and its owner account were created at sign-up.
 * {@code org-team-service} consumes this to build its own membership aggregate and publish
 * {@code OrgMemberAdded} for the owner — {@code identity-service} never asserts that fact itself
 * (see {@code docs/identity-service/ARCHITECTURE.md} §Event contracts).
 */
public record OrgProvisioned(
        UUID eventId,
        String eventType,
        String orgId,
        Instant occurredAt,
        String orgName,
        String slug,
        String ownerUserId,
        String ownerEmail,
        String ownerDisplayName)
        implements PlatformEvent {

    public static final String TYPE = Topics.ORG_PROVISIONED;

    public static OrgProvisioned of(
            String orgId, String orgName, String slug, String ownerUserId, String ownerEmail, String ownerDisplayName) {
        return new OrgProvisioned(
                UUID.randomUUID(),
                TYPE,
                orgId,
                Instant.now(),
                orgName,
                slug,
                ownerUserId,
                ownerEmail,
                ownerDisplayName);
    }
}
