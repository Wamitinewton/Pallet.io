import { infiniteQueryOptions, queryOptions, type QueryKey } from "@tanstack/react-query";
import type { GetUnreadCount } from "../application/get-unread-count";
import type { ListNotifications } from "../application/list-notifications";
import { READ_FILTERS, type ReadFilter } from "../domain/notification";

/** The gateway allows 60 requests a minute per caller; the bell spends at most one of them. */
export const UNREAD_COUNT_REFRESH_MS = 60_000;

/** Rooted at the person rather than an organization: notifications follow the account across all of them. */
export const notificationKeys = {
    all: ["notifications"] as const,
    unreadCount: () => [...notificationKeys.all, "unread-count"] as const,
    /** Every loaded page of both filters, so one change to a notification reaches all of them. */
    lists: () => [...notificationKeys.all, "list"] as const,
    list: (filter: ReadFilter) => [...notificationKeys.lists(), filter] as const,
};

export const notificationQueries = {
    unreadCount: (getUnreadCount: GetUnreadCount) =>
        queryOptions({
            queryKey: notificationKeys.unreadCount(),
            queryFn: () => getUnreadCount(),
        }),
    list: (listNotifications: ListNotifications, filter: ReadFilter) =>
        infiniteQueryOptions({
            queryKey: notificationKeys.list(filter),
            queryFn: ({ pageParam }) => listNotifications(filter, pageParam),
            initialPageParam: 0,
            getNextPageParam: (last) => (last.isLast ? undefined : last.page + 1),
        }),
};

/** The filter a cached list was asked with, read back from its key. */
export function filterOf(key: QueryKey): ReadFilter | undefined {
    const last = key.at(-1);
    return READ_FILTERS.find((filter) => filter === last);
}
