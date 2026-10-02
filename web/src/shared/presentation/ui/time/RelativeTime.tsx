"use client";

import type { ComponentProps } from "react";
import { useClock } from "../../providers/ClockProvider";
import { formatAbsoluteTime, formatRelativeTime } from "./relative-time";

export type RelativeTimeProps = Omit<ComponentProps<"time">, "dateTime" | "children"> & {
    dateTime: string | Date;
    now?: Date;
};

export function RelativeTime({ dateTime, now, ...props }: RelativeTimeProps) {
    const clock = useClock();
    const date = typeof dateTime === "string" ? new Date(dateTime) : dateTime;
    if (Number.isNaN(date.getTime())) return null;

    return (
        <time dateTime={date.toISOString()} title={formatAbsoluteTime(date)} suppressHydrationWarning {...props}>
            {formatRelativeTime(date, now ?? clock.now())}
        </time>
    );
}
