"use client";

import type { AppId, OrgId } from "@/shared/domain/ids";
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import type { RepoLink } from "../domain/repo-link";
import { repoLinkQueries } from "./queries";
import { useRepoLinkUseCases } from "./repo-link-use-cases";

/** The app's link, or null when it has none; `staleTime` lets the apps list read each row less often. */
export function useRepoLink(
    orgId: OrgId,
    appId: AppId,
    { staleTime }: { readonly staleTime?: number } = {},
): UseQueryResult<RepoLink | null> {
    const { getRepoLink } = useRepoLinkUseCases();
    return useQuery({
        ...repoLinkQueries.link(getRepoLink, orgId, appId),
        ...(staleTime !== undefined && { staleTime }),
    });
}
