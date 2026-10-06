"use client";

import { useSyncExternalStore } from "react";

/**
 * Next.js changes a same-page hash with `history.pushState`, which fires no `hashchange`; the Navigation API sees it.
 * Next calls `pushState` from an insertion effect, where React forbids scheduling an update, so the
 * notification waits for that commit to finish.
 */
function subscribe(notify: () => void): () => void {
    const navigation = "navigation" in window ? (window.navigation as EventTarget) : undefined;
    let subscribed = true;
    const notifyAfterCommit = () => {
        queueMicrotask(() => {
            if (subscribed) notify();
        });
    };
    window.addEventListener("hashchange", notify);
    window.addEventListener("popstate", notify);
    navigation?.addEventListener("currententrychange", notifyAfterCommit);
    return () => {
        subscribed = false;
        window.removeEventListener("hashchange", notify);
        window.removeEventListener("popstate", notify);
        navigation?.removeEventListener("currententrychange", notifyAfterCommit);
    };
}

const readHash = () => decodeURIComponent(window.location.hash.slice(1));

/** The address's fragment without `#`; empty while rendering on the server. */
export function useLocationHash(): string {
    return useSyncExternalStore(subscribe, readHash, () => "");
}
