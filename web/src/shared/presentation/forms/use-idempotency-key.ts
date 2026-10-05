"use client";

import { useMemo, useRef } from "react";

/**
 * One key per user intent: `current()` hands out the same key until `renew()` marks the intent as
 * changed, so a retry of the same request replays instead of repeating it.
 */
export interface IdempotencyKey {
    readonly current: () => string;
    readonly renew: () => void;
}

export function useIdempotencyKey(): IdempotencyKey {
    const key = useRef<string | undefined>(undefined);
    return useMemo(
        () => ({
            current: () => (key.current ??= crypto.randomUUID()),
            renew: () => {
                key.current = undefined;
            },
        }),
        [],
    );
}
