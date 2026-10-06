import { organizationPath } from "@/modules/organizations";
import type { AppId, OrgId, TeamId } from "@/shared/domain/ids";
import type { Route } from "next";

export function appsPath(orgId: OrgId, filter: { readonly team?: TeamId } = {}): Route {
    const base = organizationPath(orgId, "apps");
    return (filter.team === undefined ? base : `${base}?team=${encodeURIComponent(filter.team)}`) as Route;
}

export function appPath(orgId: OrgId, appId: AppId): Route {
    return `${appsPath(orgId)}/${encodeURIComponent(appId)}` as Route;
}

export function orgOverviewPath(orgId: OrgId): Route {
    return organizationPath(orgId) as Route;
}
