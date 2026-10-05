"use client";

import { useQueryClient } from "@tanstack/react-query";
import type { Route } from "next";
import { useRouter } from "next/navigation";
import { useCallback } from "react";

/**
 * Leaves for `path` with nothing from the session being left behind: the query cache is emptied and the
 * router cache refreshed, so neither a back navigation nor the next account can see the previous data.
 */
export function useNavigateAcrossSession(): (path: string) => void {
    const queryClient = useQueryClient();
    const router = useRouter();
    return useCallback(
        (path: string) => {
            queryClient.clear();
            router.replace(path as Route);
            router.refresh();
        },
        [queryClient, router],
    );
}
