"use client";

import { forgetGitHubSession, githubKeys, isGitHubSessionGone } from "@/modules/github";
import { ApiError } from "@/shared/domain/errors";
import type { AppId, InstallationId, OrgId } from "@/shared/domain/ids";
import { useMutation, useQueryClient, type QueryClient, type UseMutationResult } from "@tanstack/react-query";
import type { LinkRepositoryCommand } from "../application/link-repository";
import type { RequestManualBuildCommand } from "../application/request-manual-build";
import type { TuneRepoLinkOutcome } from "../application/tune-repo-link";
import type { BuildSettingsForm } from "../domain/build-settings";
import type { ManualBuild } from "../domain/manual-build";
import type { RepoLink } from "../domain/repo-link";
import {
    CONCURRENT_MODIFICATION,
    INSTALLATION_NOT_ACCESSIBLE,
    INSTALLATION_NOT_FOUND,
    INSTALLATION_SUSPENDED,
    REPO_LINK_EXISTS,
    REPO_LINK_NOT_FOUND,
    REPOSITORY_ARCHIVED,
    REPOSITORY_NOT_ACCESSIBLE,
    REPOSITORY_PERMISSION_TOO_LOW,
} from "../domain/repo-link-errors";
import { repoLinkKeys } from "./queries";
import { useRepoLinkUseCases } from "./repo-link-use-cases";

function isAny(error: unknown, codes: readonly string[]): boolean {
    return error instanceof ApiError && codes.some((code) => error.is(code));
}

/** The link changed under this screen: whatever the cache holds is read again rather than trusted. */
const STALE_LINK = [REPO_LINK_EXISTS, REPO_LINK_NOT_FOUND, CONCURRENT_MODIFICATION];
/** What the picker showed about a repository was wrong, so its rows are read again. */
const STALE_REPOSITORY = [REPOSITORY_NOT_ACCESSIBLE, REPOSITORY_PERMISSION_TOO_LOW, REPOSITORY_ARCHIVED];
/** What the organization's installations list showed was wrong. */
const STALE_INSTALLATION = [INSTALLATION_NOT_FOUND, INSTALLATION_SUSPENDED, INSTALLATION_NOT_ACCESSIBLE];

function settleFailure(
    queryClient: QueryClient,
    error: unknown,
    { orgId, appId, installationId }: { orgId: OrgId; appId: AppId; installationId?: InstallationId },
): void {
    if (isGitHubSessionGone(error)) forgetGitHubSession(queryClient);
    if (isAny(error, STALE_LINK)) void queryClient.invalidateQueries({ queryKey: repoLinkKeys.link(orgId, appId) });
    if (installationId !== undefined && isAny(error, STALE_REPOSITORY)) {
        void queryClient.invalidateQueries({ queryKey: repoLinkKeys.repositories(orgId, installationId) });
    }
    if (isAny(error, STALE_INSTALLATION)) {
        void queryClient.invalidateQueries({ queryKey: githubKeys.installations(orgId), exact: true });
    }
}

export type LinkRepositoryVariables = Omit<LinkRepositoryCommand, "orgId" | "appId">;

/** The answer is the new link, seeded so the app page opens on it without another read. */
export function useLinkRepository(
    orgId: OrgId,
    appId: AppId,
): UseMutationResult<RepoLink, unknown, LinkRepositoryVariables> {
    const { linkRepository } = useRepoLinkUseCases();
    const queryClient = useQueryClient();

    return useMutation({
        mutationFn: (variables) => linkRepository({ orgId, appId, ...variables }),
        onSuccess: (link) => {
            queryClient.setQueryData(repoLinkKeys.link(orgId, appId), link);
        },
        onError: (error, { installationId }) => {
            settleFailure(queryClient, error, { orgId, appId, installationId });
        },
    });
}

/** Whatever the backend answers with is the link as it is now, whether this save landed or someone else's did. */
export function useTuneRepoLink(
    orgId: OrgId,
    link: RepoLink,
): UseMutationResult<TuneRepoLinkOutcome, unknown, BuildSettingsForm> {
    const { tuneRepoLink } = useRepoLinkUseCases();
    const queryClient = useQueryClient();
    const { appId } = link;

    return useMutation({
        mutationFn: (settings) => tuneRepoLink({ orgId, link, settings }),
        onSuccess: (outcome) => {
            const current = outcome.status === "updated" ? outcome.link : outcome.latest;
            if (current !== link) queryClient.setQueryData(repoLinkKeys.link(orgId, appId), current);
        },
        onError: (error) => {
            settleFailure(queryClient, error, { orgId, appId });
        },
    });
}

export function useTakeOverVerification(orgId: OrgId, appId: AppId): UseMutationResult<RepoLink, unknown, void> {
    const { takeOverVerification } = useRepoLinkUseCases();
    const queryClient = useQueryClient();

    return useMutation({
        mutationFn: () => takeOverVerification(orgId, appId),
        onSuccess: (link) => {
            queryClient.setQueryData(repoLinkKeys.link(orgId, appId), link);
        },
        onError: (error) => {
            settleFailure(queryClient, error, { orgId, appId });
        },
    });
}

/** The backend keeps the link as unlinked on purpose, so it is read again rather than guessed at. */
export function useDisconnectRepoLink(orgId: OrgId, appId: AppId): UseMutationResult<void, unknown, void> {
    const { disconnectRepoLink } = useRepoLinkUseCases();
    const queryClient = useQueryClient();
    const invalidate = () => queryClient.invalidateQueries({ queryKey: repoLinkKeys.link(orgId, appId) });

    return useMutation({
        mutationFn: () => disconnectRepoLink(orgId, appId),
        onSuccess: invalidate,
        onError: (error) => {
            if (isAny(error, [REPO_LINK_NOT_FOUND])) void invalidate();
        },
    });
}

export type RequestManualBuildVariables = Omit<RequestManualBuildCommand, "orgId" | "appId">;

export function useRequestManualBuild(
    orgId: OrgId,
    appId: AppId,
): UseMutationResult<ManualBuild, unknown, RequestManualBuildVariables> {
    const { requestManualBuild } = useRepoLinkUseCases();
    const queryClient = useQueryClient();

    return useMutation({
        mutationFn: (variables) => requestManualBuild({ orgId, appId, ...variables }),
        onError: (error) => {
            settleFailure(queryClient, error, { orgId, appId });
        },
    });
}
