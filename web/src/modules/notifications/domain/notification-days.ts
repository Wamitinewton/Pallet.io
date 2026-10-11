import type { Notification } from "./notification";

export type NotificationDay =
    | { readonly kind: "today" }
    | { readonly kind: "yesterday" }
    | { readonly kind: "this-week" }
    | { readonly kind: "month"; readonly year: number; readonly month: number; readonly thisYear: boolean };

export interface NotificationDayGroup {
    /** Stable across renders, for a React key and a heading id. */
    readonly key: string;
    readonly day: NotificationDay;
    readonly notifications: readonly Notification[];
}

const DAY_MS = 24 * 60 * 60 * 1000;
const DAYS_IN_A_WEEK = 7;

interface CalendarDate {
    readonly year: number;
    readonly month: number;
    readonly day: number;
}

function calendarDate(instant: Date, timeZone: string): CalendarDate {
    const parts = new Intl.DateTimeFormat("en-US", {
        timeZone,
        year: "numeric",
        month: "numeric",
        day: "numeric",
    }).formatToParts(instant);
    const part = (type: Intl.DateTimeFormatPartTypes) => Number(parts.find((p) => p.type === type)?.value);
    return { year: part("year"), month: part("month"), day: part("day") };
}

function epochDay({ year, month, day }: CalendarDate): number {
    return Math.round(Date.UTC(year, month - 1, day) / DAY_MS);
}

function dayOf(sentAt: Date, today: CalendarDate, timeZone: string): NotificationDay {
    const date = calendarDate(sentAt, timeZone);
    const daysAgo = epochDay(today) - epochDay(date);
    if (daysAgo <= 0) return { kind: "today" };
    if (daysAgo === 1) return { kind: "yesterday" };
    if (daysAgo < DAYS_IN_A_WEEK) return { kind: "this-week" };
    return { kind: "month", year: date.year, month: date.month, thisYear: date.year === today.year };
}

function keyOf(day: NotificationDay): string {
    return day.kind === "month" ? `${String(day.year)}-${String(day.month)}` : day.kind;
}

/**
 * Splits a newest-first list into the calendar days of `timeZone`, as of `now`. An undated delivery stays
 * with the one before it, since the list is already in delivery order.
 */
export function groupByDay(
    notifications: readonly Notification[],
    now: Date,
    timeZone: string,
): readonly NotificationDayGroup[] {
    const today = calendarDate(now, timeZone);
    const groups: { key: string; day: NotificationDay; notifications: Notification[] }[] = [];

    for (const notification of notifications) {
        const previous = groups.at(-1);
        const day =
            notification.sentAt === null
                ? (previous?.day ?? { kind: "today" as const })
                : dayOf(new Date(notification.sentAt), today, timeZone);
        const key = keyOf(day);
        if (previous?.key === key) previous.notifications.push(notification);
        else groups.push({ key, day, notifications: [notification] });
    }
    return groups;
}
