"use client";

import type { AppId, OrgId } from "@/shared/domain/ids";
import { useQueries, type UseQueryResult } from "@tanstack/react-query";
import type { RepoLink } from "../domain/repo-link";
import { LIST_BADGE_STALE_MS, repoLinkQueries } from "./queries";
import { useRepoLinkUseCases } from "./repo-link-use-cases";

/**
 * Several apps' links, one read each and in the order given, each trusted for a minute; a batch endpoint
 * would make this one request, so callers keep `appIds` to a handful.
 */
export function useRepoLinks(orgId: OrgId, appIds: readonly AppId[]): readonly UseQueryResult<RepoLink | null>[] {
    const { getRepoLink } = useRepoLinkUseCases();
    return useQueries({
        queries: appIds.map((appId) => ({
            ...repoLinkQueries.link(getRepoLink, orgId, appId),
            staleTime: LIST_BADGE_STALE_MS,
        })),
    });
}
