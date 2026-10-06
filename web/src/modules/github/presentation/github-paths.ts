import { organizationPath } from "@/modules/organizations";
import type { OrgId } from "@/shared/domain/ids";
import type { Route } from "next";

export const GITHUB_CALLBACK_PATH = "/github/callback" as Route;

/** `connect=existing` opens the connect dialog on the existing-installation step, where a sign-in returns to. */
export function githubPath(orgId: OrgId, options: { readonly connect?: "existing" } = {}): Route {
    const base = organizationPath(orgId, "github");
    return (options.connect === undefined ? base : `${base}?connect=${options.connect}`) as Route;
}

export function appsPath(orgId: OrgId): Route {
    return organizationPath(orgId, "apps") as Route;
}

export function orgOverviewPath(orgId: OrgId): Route {
    return organizationPath(orgId) as Route;
}
