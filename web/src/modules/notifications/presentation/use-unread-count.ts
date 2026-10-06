"use client";

import { useQuery } from "@tanstack/react-query";
import { useNotificationUseCases } from "./notification-use-cases";
import { notificationQueries, UNREAD_COUNT_REFRESH_MS } from "./queries";

export interface UnreadCountOptions {
    /** Only one observer polls; every other one reads the same cache entry. */
    readonly poll?: boolean;
}

export function useUnreadCount({ poll = false }: UnreadCountOptions = {}): number | undefined {
    const { getUnreadCount } = useNotificationUseCases();
    return useQuery({
        ...notificationQueries.unreadCount(getUnreadCount),
        ...(poll && { refetchInterval: UNREAD_COUNT_REFRESH_MS, refetchOnWindowFocus: true }),
    }).data;
}
