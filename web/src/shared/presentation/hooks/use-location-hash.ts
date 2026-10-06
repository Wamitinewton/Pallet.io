"use client";

import { useSyncExternalStore } from "react";

/** Next.js changes a same-page hash with `history.pushState`, which fires no `hashchange`; the Navigation API sees it. */
function subscribe(notify: () => void): () => void {
    const navigation = "navigation" in window ? (window.navigation as EventTarget) : undefined;
    window.addEventListener("hashchange", notify);
    window.addEventListener("popstate", notify);
    navigation?.addEventListener("currententrychange", notify);
    return () => {
        window.removeEventListener("hashchange", notify);
        window.removeEventListener("popstate", notify);
        navigation?.removeEventListener("currententrychange", notify);
    };
}

const readHash = () => decodeURIComponent(window.location.hash.slice(1));

/** The address's fragment without `#`; empty while rendering on the server. */
export function useLocationHash(): string {
    return useSyncExternalStore(subscribe, readHash, () => "");
}
