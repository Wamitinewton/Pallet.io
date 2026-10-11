import { makeGetUnreadCount, type GetUnreadCount } from "./get-unread-count";
import { makeListNotifications, type ListNotifications } from "./list-notifications";
import { makeMarkAllRead, type MarkAllRead } from "./mark-all-read";
import { makeMarkRead, type MarkRead } from "./mark-read";
import type { NotificationRepository } from "./ports";

export interface NotificationUseCases {
    readonly getUnreadCount: GetUnreadCount;
    readonly listNotifications: ListNotifications;
    readonly markRead: MarkRead;
    readonly markAllRead: MarkAllRead;
}

export interface NotificationDependencies {
    readonly notifications: NotificationRepository;
}

export function makeNotificationUseCases({ notifications }: NotificationDependencies): NotificationUseCases {
    return {
        getUnreadCount: makeGetUnreadCount(notifications),
        listNotifications: makeListNotifications(notifications),
        markRead: makeMarkRead(notifications),
        markAllRead: makeMarkAllRead(notifications),
    };
}
