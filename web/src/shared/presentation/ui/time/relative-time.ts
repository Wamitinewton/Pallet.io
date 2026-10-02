type Unit = readonly [Intl.RelativeTimeFormatUnit, number];

const MINUTE: Unit = ["minute", 60];

const UNITS: readonly Unit[] = [
    ["year", 365 * 24 * 60 * 60],
    ["month", 30 * 24 * 60 * 60],
    ["week", 7 * 24 * 60 * 60],
    ["day", 24 * 60 * 60],
    ["hour", 60 * 60],
    MINUTE,
];

const relative = new Intl.RelativeTimeFormat("en", { numeric: "auto" });
const absolute = new Intl.DateTimeFormat("en", { dateStyle: "medium", timeStyle: "short" });

export function formatRelativeTime(date: Date, now: Date): string {
    const seconds = Math.round((date.getTime() - now.getTime()) / 1000);
    if (Math.abs(seconds) < 45) return seconds <= 0 ? "just now" : "in a few seconds";
    const [unit, size] = UNITS.find(([, length]) => Math.abs(seconds) >= length) ?? MINUTE;
    return relative.format(Math.round(seconds / size), unit);
}

export function formatAbsoluteTime(date: Date): string {
    return absolute.format(date);
}
