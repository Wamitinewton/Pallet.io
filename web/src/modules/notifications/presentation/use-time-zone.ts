"use client";

import { useSyncExternalStore } from "react";

const SERVER_TIME_ZONE = "UTC";

const subscribe = () => () => undefined;
const readTimeZone = () => Intl.DateTimeFormat().resolvedOptions().timeZone;

/** The reader's time zone; UTC while server rendering and hydrating, so both render the same markup. */
export function useTimeZone(): string {
    return useSyncExternalStore(subscribe, readTimeZone, () => SERVER_TIME_ZONE);
}
