"use client";

import { useEffect } from "react";

/**
 * Rewrites the current history entry to `path` without a navigation, so a secret carried in the query
 * leaves the address bar and history while the page keeps it in memory.
 */
export function useReplaceAddress(path: string): void {
    useEffect(() => {
        const { pathname, search, hash } = window.location;
        if (`${pathname}${search}${hash}` !== path) window.history.replaceState(null, "", path);
    }, [path]);
}
