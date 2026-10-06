"use client";

import { ApiError } from "@/shared/domain/errors";
import type { InstallationId, OrgId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import { queryScopes } from "@/shared/presentation/query";
import { useMutation, useQueryClient, type QueryClient, type UseMutationResult } from "@tanstack/react-query";
import { GITHUB_AUTHORIZATION_REQUIRED, INSTALLATION_NOT_FOUND } from "../domain/github-errors";
import type { InstallationLink, LinkOutcome, LinkRequest } from "../domain/installation";
import { useGitHubUseCases } from "./github-use-cases";
import { githubKeys } from "./queries";

/** The service no longer has a GitHub session for the caller, whatever the cache last read. */
export function forgetGitHubSession(queryClient: QueryClient): void {
    queryClient.setQueryData(githubKeys.session(), null);
    queryClient.removeQueries({ queryKey: githubKeys.visible() });
}

export function isGitHubSessionGone(error: unknown): boolean {
    return error instanceof ApiError && error.is(GITHUB_AUTHORIZATION_REQUIRED);
}

export function useEndGitHubSession(): UseMutationResult<void, Error, void> {
    const { endGitHubSession } = useGitHubUseCases();
    const queryClient = useQueryClient();

    return useMutation({
        mutationFn: endGitHubSession,
        onSuccess: () => {
            forgetGitHubSession(queryClient);
        },
    });
}

export function useLinkInstallation(orgId: OrgId): UseMutationResult<LinkOutcome, Error, LinkRequest> {
    const { linkInstallation } = useGitHubUseCases();
    const queryClient = useQueryClient();

    return useMutation({
        mutationFn: (request) => linkInstallation(orgId, request),
        onSuccess: () => {
            void queryClient.invalidateQueries({ queryKey: githubKeys.org(orgId) });
        },
        onError: (error) => {
            if (isGitHubSessionGone(error)) forgetGitHubSession(queryClient);
        },
    });
}

/** Unlinking disconnects the apps that built through it, so their repository links are read again too. */
export function useUnlinkInstallation(orgId: OrgId): UseMutationResult<void, Error, InstallationId> {
    const { unlinkInstallation } = useGitHubUseCases();
    const queryClient = useQueryClient();

    const settle = (installationId: InstallationId) => {
        queryClient.setQueryData<Page<InstallationLink>>(githubKeys.installations(orgId), (page) => {
            if (page === undefined) return undefined;
            const items = page.items.filter((link) => link.installationId !== installationId);
            return { ...page, items, totalItems: page.totalItems - (page.items.length - items.length) };
        });
        void queryClient.invalidateQueries({ queryKey: githubKeys.org(orgId) });
        void queryClient.invalidateQueries({ queryKey: queryScopes.apps(orgId) });
    };

    return useMutation({
        mutationFn: (installationId) => unlinkInstallation(orgId, installationId),
        onSuccess: (_, installationId) => {
            settle(installationId);
        },
        onError: (error, installationId) => {
            if (error instanceof ApiError && error.is(INSTALLATION_NOT_FOUND)) settle(installationId);
        },
    });
}
