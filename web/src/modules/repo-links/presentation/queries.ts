import { appKeys } from "@/modules/apps";
import { githubKeys } from "@/modules/github";
import type { AppId, InstallationId, OrgId } from "@/shared/domain/ids";
import { queryOptions } from "@tanstack/react-query";
import type { GetRepoLink } from "../application/get-repo-link";
import type { ListLinkableRepositories } from "../application/list-linkable-repositories";
import type { RepositoryQuery } from "../domain/picker-repository";

/** How long the apps list trusts a row's link before reading it again: one read per visible row is the cost. */
export const LIST_BADGE_STALE_MS = 60_000;

export const repoLinkKeys = {
    /** Beneath the app, so deleting the app drops its link, and unlinking an installation reaches every app's. */
    link: (orgId: OrgId, appId: AppId) => [...appKeys.app(orgId, appId), "repo-link"] as const,
    /** Beneath the organization's installations, which unlinking one invalidates. */
    repositories: (orgId: OrgId, installationId: InstallationId) =>
        [...githubKeys.installations(orgId), installationId, "repositories"] as const,
    repositoryPage: (orgId: OrgId, installationId: InstallationId, query: RepositoryQuery) =>
        [...repoLinkKeys.repositories(orgId, installationId), query] as const,
};

export const repoLinkQueries = {
    link: (getRepoLink: GetRepoLink, orgId: OrgId, appId: AppId) =>
        queryOptions({
            queryKey: repoLinkKeys.link(orgId, appId),
            queryFn: () => getRepoLink(orgId, appId),
        }),
    repositories: (
        listLinkableRepositories: ListLinkableRepositories,
        orgId: OrgId,
        installationId: InstallationId,
        query: RepositoryQuery,
    ) =>
        queryOptions({
            queryKey: repoLinkKeys.repositoryPage(orgId, installationId, query),
            queryFn: () => listLinkableRepositories(orgId, installationId, query),
        }),
};
