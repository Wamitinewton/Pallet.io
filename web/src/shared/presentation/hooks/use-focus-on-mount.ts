"use client";

import { useEffect, useRef, type RefObject } from "react";

/** Moves focus to the returned element once it mounts, so a state that replaces the form is announced. */
export function useFocusOnMount<T extends HTMLElement>(enabled = true): RefObject<T | null> {
    const ref = useRef<T>(null);
    useEffect(() => {
        if (enabled) ref.current?.focus();
    }, [enabled]);
    return ref;
}
