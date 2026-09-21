package io.pallet.orgteam.org;

import java.time.Instant;

public record OrgDto(
        String orgId,
        String name,
        String slug,
        OrgStatus status,
        String ownerUserId,
        Instant createdAt,
        Counts counts) {

    public record Counts(long members, long teams, long apps) {}

    static OrgDto of(Organization org, Counts counts) {
        return new OrgDto(
                org.getOrgId(),
                org.getName(),
                org.getSlug(),
                org.getStatus(),
                org.getOwnerUserId(),
                org.getCreatedAt(),
                counts);
    }
}
