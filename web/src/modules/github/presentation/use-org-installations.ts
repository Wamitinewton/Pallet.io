"use client";

import type { OrgId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import type { InstallationLink } from "../domain/installation";
import { useGitHubUseCases } from "./github-use-cases";
import { githubQueries } from "./queries";

/** The installations linked to the organization, newest first; `enabled: false` sends nothing. */
export function useOrgInstallations(
    orgId: OrgId,
    { enabled = true }: { readonly enabled?: boolean } = {},
): UseQueryResult<Page<InstallationLink>> {
    const { listOrgInstallations } = useGitHubUseCases();
    return useQuery({ ...githubQueries.installations(listOrgInstallations, orgId), enabled });
}
