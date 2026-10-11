import type { ReadFilter } from "../domain/notification";
import type { NotificationDay } from "../domain/notification-days";

export const PAGE_TITLE_COPY = "Notifications";
export const PAGE_DESCRIPTION_COPY =
    "Updates about your organizations, apps and account. We also email you the important ones.";
export const FILTER_GROUP_LABEL_COPY = "Show";
export const MARK_ALL_READ_COPY = "Mark all as read";
export const MARK_READ_COPY = "Mark as read";
export const UNREAD_DOT_COPY = "Unread";
export const LOAD_MORE_COPY = "Load more";
export const LOADING_COPY = "Loading notifications";
export const LOAD_MORE_ERROR_TITLE_COPY = "Couldn't load more notifications.";

export const MARKED_READ_COPY = "Marked as read";
export const MARKED_ALL_READ_COPY = "All notifications marked as read";

export const FILTER_LABEL: Readonly<Record<ReadFilter, string>> = {
    ALL: "All",
    UNREAD: "Unread",
};

export const EMPTY_COPY: Readonly<Record<ReadFilter, { readonly title: string; readonly description: string }>> = {
    ALL: {
        title: "No notifications yet",
        description: "Updates about your apps, invites and account will show up here.",
    },
    UNREAD: {
        title: "You're all caught up",
        description: "New notifications about your apps, invites and account will show up here.",
    },
};

export function markReadLabel(title: string): string {
    return `${MARK_READ_COPY}: ${title}`;
}

export function unreadCountCopy(count: number): string {
    return `${count.toLocaleString("en")} unread`;
}

export function showingCopy(shown: number, total: number): string {
    return `Showing ${shown.toLocaleString("en")} of ${total.toLocaleString("en")}`;
}

const monthName = new Intl.DateTimeFormat("en-GB", { month: "long", timeZone: "UTC" });
const monthAndYear = new Intl.DateTimeFormat("en-GB", { month: "long", year: "numeric", timeZone: "UTC" });

export function dayHeading(day: NotificationDay): string {
    switch (day.kind) {
        case "today":
            return "Today";
        case "yesterday":
            return "Yesterday";
        case "this-week":
            return "Earlier this week";
        case "month": {
            const firstOfMonth = new Date(Date.UTC(day.year, day.month - 1, 1));
            return (day.thisYear ? monthName : monthAndYear).format(firstOfMonth);
        }
    }
}
