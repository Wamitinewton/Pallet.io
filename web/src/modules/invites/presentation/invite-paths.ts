import { organizationPath } from "@/modules/organizations";
import type { OrgId } from "@/shared/domain/ids";
import type { Route } from "next";

/** The page `org-team-service` links every invite email to. */
export function invitePath(token: string): Route {
    return `/invites/${encodeURIComponent(token)}` as Route;
}

/** The members page opened on its invites tab. */
export function pendingInvitesPath(orgId: OrgId): Route {
    return `${organizationPath(orgId, "members")}?tab=invites` as Route;
}
