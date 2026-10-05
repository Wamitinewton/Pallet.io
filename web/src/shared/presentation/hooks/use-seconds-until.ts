"use client";

import { secondsUntil } from "@/shared/domain/request-failure";
import { useCallback, useSyncExternalStore } from "react";
import { useClock } from "../providers/ClockProvider";

const TICK_MS = 250;

/** Whole seconds left before `deadline`, read from the injected clock; the timer only prompts a re-read. */
export function useSecondsUntil(deadline: Date | undefined): number {
    const clock = useClock();
    const subscribe = useCallback(
        (notify: () => void) => {
            if (deadline === undefined) return () => undefined;
            const timer = setInterval(() => {
                notify();
                if (secondsUntil(deadline, clock.now()) === 0) clearInterval(timer);
            }, TICK_MS);
            return () => {
                clearInterval(timer);
            };
        },
        [clock, deadline],
    );
    return useSyncExternalStore(
        subscribe,
        () => (deadline === undefined ? 0 : secondsUntil(deadline, clock.now())),
        () => 0,
    );
}
