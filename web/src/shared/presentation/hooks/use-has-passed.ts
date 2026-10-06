"use client";

import { useCallback, useSyncExternalStore } from "react";
import { useClock } from "../providers/ClockProvider";

const TICK_MS = 250;

/** Whether the injected clock has reached `deadline`; never true during server rendering. */
export function useHasPassed(deadline: Date): boolean {
    const clock = useClock();
    const passed = useCallback(() => clock.now().getTime() >= deadline.getTime(), [clock, deadline]);
    const subscribe = useCallback(
        (notify: () => void) => {
            const timer = setInterval(() => {
                notify();
                if (passed()) clearInterval(timer);
            }, TICK_MS);
            return () => {
                clearInterval(timer);
            };
        },
        [passed],
    );
    return useSyncExternalStore(subscribe, passed, () => false);
}
