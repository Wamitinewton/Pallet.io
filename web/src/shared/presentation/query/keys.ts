import type { OrgId } from "@/shared/domain/ids";

/**
 * Key roots that more than one module reads or invalidates. Each module builds its own keys beneath
 * these, so a module can refresh another's cache without importing it.
 */
export const queryScopes = {
    myOrganizations: () => ["orgs", "mine"] as const,
    /** Everything scoped to one organization. */
    org: (orgId: OrgId) => ["org", orgId] as const,
    /** The organization itself, with the member, team and app counts. */
    organization: (orgId: OrgId) => [...queryScopes.org(orgId), "organization"] as const,
    members: (orgId: OrgId) => [...queryScopes.org(orgId), "members"] as const,
    teams: (orgId: OrgId) => [...queryScopes.org(orgId), "teams"] as const,
    apps: (orgId: OrgId) => [...queryScopes.org(orgId), "apps"] as const,
};
