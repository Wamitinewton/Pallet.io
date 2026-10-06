import type { OrgId } from "@/shared/domain/ids";
import type { OrgSummary } from "../domain/organization";

/** The last organization used while it is still mine, else the first team, else the personal one. */
export function chooseLandingOrganization(
    organizations: readonly OrgSummary[],
    lastOrgId: string | undefined,
): OrgId | undefined {
    const last = organizations.find((org) => org.orgId === lastOrgId);
    const team = organizations.find((org) => org.kind === "TEAM");
    return (last ?? team ?? organizations[0])?.orgId;
}
