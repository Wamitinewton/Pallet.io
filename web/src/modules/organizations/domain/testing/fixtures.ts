import { asOrgId, asUserId } from "@/shared/domain/ids";
import { asIsoInstant } from "@/shared/domain/instant";
import type { Organization, OrgSummary } from "../organization";

export function anOrgSummary(overrides: Partial<OrgSummary> = {}): OrgSummary {
    return {
        orgId: asOrgId("org-kilima"),
        name: "Kilima Labs",
        slug: "kilima-labs",
        kind: "TEAM",
        myRole: "OWNER",
        ...overrides,
    };
}

export function anOrganization(overrides: Partial<Organization> = {}): Organization {
    return {
        orgId: asOrgId("org-kilima"),
        name: "Kilima Labs",
        slug: "kilima-labs",
        kind: "TEAM",
        status: "ACTIVE",
        ownerUserId: asUserId("user-amani"),
        createdAt: asIsoInstant("2026-01-02T09:00:00Z"),
        counts: { members: 6, teams: 3, apps: 4 },
        ...overrides,
    };
}
