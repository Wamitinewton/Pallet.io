"use client";

import { useEffect, useRef, type RefObject } from "react";

/** Starts loading this far before the end of the list comes into view. */
const ROOT_MARGIN = "320px 0px";

/**
 * Calls `load` whenever the returned element scrolls into view while `enabled`. Where the browser can't
 * observe intersections nothing happens, and the visible "Load more" button stays the way in.
 */
export function useLoadOnScroll<T extends Element>(load: () => void, enabled: boolean): RefObject<T | null> {
    const sentinel = useRef<T>(null);
    const latestLoad = useRef(load);

    useEffect(() => {
        latestLoad.current = load;
    }, [load]);

    useEffect(() => {
        const element = sentinel.current;
        if (!enabled || element === null || typeof IntersectionObserver === "undefined") return;
        const observer = new IntersectionObserver(
            (entries) => {
                if (entries.some((entry) => entry.isIntersecting)) latestLoad.current();
            },
            { rootMargin: ROOT_MARGIN },
        );
        observer.observe(element);
        return () => {
            observer.disconnect();
        };
    }, [enabled]);

    return sentinel;
}
