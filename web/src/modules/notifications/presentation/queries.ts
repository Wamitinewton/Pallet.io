import { queryOptions } from "@tanstack/react-query";
import type { GetUnreadCount } from "../application/get-unread-count";

/** The gateway allows 60 requests a minute per caller; the bell spends at most one of them. */
export const UNREAD_COUNT_REFRESH_MS = 60_000;

export const notificationKeys = {
    all: ["notifications"] as const,
    unreadCount: () => [...notificationKeys.all, "unread-count"] as const,
};

export const notificationQueries = {
    unreadCount: (getUnreadCount: GetUnreadCount) =>
        queryOptions({
            queryKey: notificationKeys.unreadCount(),
            queryFn: () => getUnreadCount(),
        }),
};
