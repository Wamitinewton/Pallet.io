"use client";

import { useClock } from "@/shared/presentation/providers";
import { useQuery, useQueryClient, type UseQueryResult } from "@tanstack/react-query";
import { useEffect } from "react";
import type { GitHubSession } from "../domain/github-session";
import { useGitHubUseCases } from "./github-use-cases";
import { githubKeys, githubQueries } from "./queries";

/** The caller's GitHub sign-in, read again the moment it expires on the service's schedule. */
export function useGitHubSession(): UseQueryResult<GitHubSession | null> {
    const { getGitHubSession } = useGitHubUseCases();
    const clock = useClock();
    const queryClient = useQueryClient();
    const session = useQuery(githubQueries.session(getGitHubSession));
    const expiresAt = session.data?.expiresAt;

    useEffect(() => {
        if (expiresAt === undefined) return;
        const timer = setTimeout(
            () => {
                void queryClient.invalidateQueries({ queryKey: githubKeys.mine() });
            },
            Math.max(0, Date.parse(expiresAt) - clock.now().getTime()),
        );
        return () => {
            clearTimeout(timer);
        };
    }, [expiresAt, clock, queryClient]);

    return session;
}
