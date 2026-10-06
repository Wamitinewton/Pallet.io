import type { Route } from "next";

/** The page `org-team-service` links every invite email to. */
export function invitePath(token: string): Route {
    return `/invites/${encodeURIComponent(token)}` as Route;
}
