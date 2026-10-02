"use client";

import { systemClock, type Clock } from "@/shared/domain/clock";
import { createContext, use, type ReactNode } from "react";

const ClockContext = createContext<Clock>(systemClock);

export function ClockProvider({ clock, children }: Readonly<{ clock: Clock; children: ReactNode }>) {
    return <ClockContext value={clock}>{children}</ClockContext>;
}

export function useClock(): Clock {
    return use(ClockContext);
}
