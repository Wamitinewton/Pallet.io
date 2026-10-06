"use client";

import { useMutation } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import type { StartAuthorizationCommand } from "../application/start-authorization";
import type { StartInstallCommand } from "../application/start-install";
import type { CallbackQuery, GitHubRedirectOutcome } from "../domain/callback";
import type { GitHubHandoff, ReturnIntent } from "../domain/return-intent";
import { useGitHubUseCases } from "./github-use-cases";
import { leaveForGitHub } from "./leave-for-github";

export interface GitHubHandoffController<V> {
    readonly start: (variables: V) => void;
    /** Also true once the browser is on its way to GitHub, so the control can't be used twice. */
    readonly pending: boolean;
    readonly error: Error | null;
    readonly reset: () => void;
}

/** A page restored from the back-forward cache comes back usable, not stuck on its way to GitHub. */
function useLeaving(): readonly [boolean, () => void] {
    const [leaving, setLeaving] = useState(false);

    useEffect(() => {
        const restored = (event: PageTransitionEvent) => {
            if (event.persisted) setLeaving(false);
        };
        window.addEventListener("pageshow", restored);
        return () => {
            window.removeEventListener("pageshow", restored);
        };
    }, []);

    return [
        leaving,
        () => {
            setLeaving(true);
        },
    ];
}

function useHandoff<V>(begin: (variables: V) => Promise<GitHubHandoff>): GitHubHandoffController<V> {
    const [leaving, markLeaving] = useLeaving();
    const mutation = useMutation({
        mutationFn: begin,
        onSuccess: ({ url }) => {
            markLeaving();
            leaveForGitHub(url);
        },
    });

    return {
        start: mutation.mutate,
        pending: mutation.isPending || leaving,
        error: mutation.error,
        reset: mutation.reset,
    };
}

export function useStartAuthorization(): GitHubHandoffController<StartAuthorizationCommand> {
    return useHandoff(useGitHubUseCases().startAuthorization);
}

export function useStartInstall(): GitHubHandoffController<StartInstallCommand> {
    return useHandoff(useGitHubUseCases().startInstall);
}

export function useRetryGitHubRedirect(): GitHubHandoffController<ReturnIntent> {
    return useHandoff(useGitHubUseCases().retryGitHubRedirect);
}

/**
 * Resolves with the outcome through the promise rather than the hook's state: the callback starts this from
 * its first effect, and a strict-mode remount detaches that effect's observer before the answer arrives.
 */
export function useFinishGitHubRedirect(): (query: CallbackQuery) => Promise<GitHubRedirectOutcome> {
    const { finishGitHubRedirect } = useGitHubUseCases();
    return useMutation({ mutationFn: finishGitHubRedirect }).mutateAsync;
}
