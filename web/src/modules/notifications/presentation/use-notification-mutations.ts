"use client";

import { instantFromDate } from "@/shared/domain/instant";
import { messageFor } from "@/shared/presentation/errors";
import { useClock } from "@/shared/presentation/providers";
import { useToast } from "@/shared/presentation/ui";
import { useMutation, useQueryClient, type QueryKey, type UseMutationResult } from "@tanstack/react-query";
import type { MarkReadOutcome } from "../application/mark-read";
import { decrementedUnread, type Notification } from "../domain/notification";
import {
    emptied,
    withAllRead,
    withNotification,
    withoutNotification,
    withRead,
    type NotificationPages,
} from "./notification-cache";
import { MARKED_ALL_READ_COPY, MARKED_READ_COPY } from "./notification-copy";
import { useNotificationUseCases } from "./notification-use-cases";
import { filterOf, notificationKeys } from "./queries";

interface Snapshot {
    readonly lists: readonly (readonly [QueryKey, NotificationPages | undefined])[];
    readonly unreadCount: number | undefined;
}

/**
 * Reads it in every loaded page of both filters and takes it off the bell at once. A failure puts that one
 * notification back as it was, so a concurrent mark of another one survives; one that no longer exists for
 * the caller is dropped instead. The unread list keeps showing it, read, until it is next fetched, so
 * nothing under the reader's pointer moves.
 */
export function useMarkRead(): UseMutationResult<MarkReadOutcome, unknown, Notification> {
    const { markRead } = useNotificationUseCases();
    const queryClient = useQueryClient();
    const clock = useClock();
    const toast = useToast();

    const setUnreadCount = (update: (count: number) => number) => {
        queryClient.setQueryData<number>(notificationKeys.unreadCount(), (count) =>
            count === undefined ? undefined : update(count),
        );
    };

    return useMutation({
        mutationFn: (notification) => markRead(notification.id),
        onMutate: async (notification) => {
            await queryClient.cancelQueries({ queryKey: notificationKeys.all });
            const at = instantFromDate(clock.now());
            queryClient.setQueriesData<NotificationPages>({ queryKey: notificationKeys.lists() }, (data) =>
                withRead(data, notification.id, at),
            );
            if (!notification.read) setUnreadCount(decrementedUnread);
        },
        onSuccess: (outcome, notification) => {
            if (outcome === "gone") {
                queryClient.setQueriesData<NotificationPages>({ queryKey: notificationKeys.lists() }, (data) =>
                    withoutNotification(data, notification.id),
                );
                return;
            }
            toast.success(MARKED_READ_COPY);
            void queryClient.invalidateQueries({ queryKey: notificationKeys.list("UNREAD"), refetchType: "none" });
        },
        onError: (error, notification) => {
            queryClient.setQueriesData<NotificationPages>({ queryKey: notificationKeys.lists() }, (data) =>
                withNotification(data, notification),
            );
            if (!notification.read) setUnreadCount((count) => count + 1);
            toast.error(messageFor(error));
        },
    });
}

/** Everything read and the bell at zero at once; restored as it was on failure, and read again either way. */
export function useMarkAllRead(): UseMutationResult<void, unknown, void, Snapshot> {
    const { markAllRead } = useNotificationUseCases();
    const queryClient = useQueryClient();
    const clock = useClock();
    const toast = useToast();

    return useMutation({
        mutationFn: () => markAllRead(),
        onMutate: async () => {
            await queryClient.cancelQueries({ queryKey: notificationKeys.all });
            const snapshot: Snapshot = {
                lists: queryClient.getQueriesData<NotificationPages>({ queryKey: notificationKeys.lists() }),
                unreadCount: queryClient.getQueryData<number>(notificationKeys.unreadCount()),
            };
            const at = instantFromDate(clock.now());
            for (const [key, data] of snapshot.lists) {
                queryClient.setQueryData(key, filterOf(key) === "UNREAD" ? emptied(data) : withAllRead(data, at));
            }
            queryClient.setQueryData(notificationKeys.unreadCount(), 0);
            return snapshot;
        },
        onSuccess: () => {
            toast.success(MARKED_ALL_READ_COPY);
        },
        onError: (error, _variables, snapshot) => {
            for (const [key, data] of snapshot?.lists ?? []) queryClient.setQueryData(key, data);
            queryClient.setQueryData(notificationKeys.unreadCount(), snapshot?.unreadCount);
            toast.error(messageFor(error));
        },
        onSettled: () => queryClient.invalidateQueries({ queryKey: notificationKeys.all }),
    });
}
