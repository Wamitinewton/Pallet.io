import { organizationPath } from "@/modules/organizations";
import type { OrgId, TeamId } from "@/shared/domain/ids";
import type { Route } from "next";

export function teamsPath(orgId: OrgId): Route {
    return organizationPath(orgId, "teams") as Route;
}

export function teamPath(orgId: OrgId, teamId: TeamId): Route {
    return `${teamsPath(orgId)}/${encodeURIComponent(teamId)}` as Route;
}

export function orgOverviewPath(orgId: OrgId): Route {
    return organizationPath(orgId) as Route;
}

/** The members page opened on its invites tab, for someone not in the organization yet. */
export function invitesPath(orgId: OrgId): Route {
    return `${organizationPath(orgId, "members")}?tab=invites` as Route;
}
