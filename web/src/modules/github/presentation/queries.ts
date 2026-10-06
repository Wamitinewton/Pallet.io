import type { OrgId } from "@/shared/domain/ids";
import type { PageRequest } from "@/shared/domain/page";
import { queryScopes } from "@/shared/presentation/query";
import { queryOptions } from "@tanstack/react-query";
import type { GetGitHubSession } from "../application/get-github-session";
import type { ListOrgInstallations } from "../application/list-org-installations";
import type { ListVisibleInstallations } from "../application/list-visible-installations";

/** The service caps a page at a hundred; no organization or GitHub user is expected to have more. */
export const INSTALLATIONS_PAGE: PageRequest = { page: 0, size: 100 };

export const githubKeys = {
    /** The caller's own GitHub sign-in and what it can see: no organization owns these. */
    mine: () => ["github"] as const,
    session: () => [...githubKeys.mine(), "session"] as const,
    visible: () => [...githubKeys.mine(), "installations"] as const,
    org: queryScopes.github,
    installations: (orgId: OrgId) => [...githubKeys.org(orgId), "installations"] as const,
};

export const githubQueries = {
    session: (getGitHubSession: GetGitHubSession) =>
        queryOptions({ queryKey: githubKeys.session(), queryFn: getGitHubSession }),
    visible: (listVisibleInstallations: ListVisibleInstallations) =>
        queryOptions({
            queryKey: githubKeys.visible(),
            queryFn: () => listVisibleInstallations(INSTALLATIONS_PAGE),
        }),
    installations: (listOrgInstallations: ListOrgInstallations, orgId: OrgId) =>
        queryOptions({
            queryKey: githubKeys.installations(orgId),
            queryFn: () => listOrgInstallations(orgId, INSTALLATIONS_PAGE),
        }),
};
