import { makeGetUnreadCount, type GetUnreadCount } from "./get-unread-count";
import type { NotificationRepository } from "./ports";

export interface NotificationUseCases {
    readonly getUnreadCount: GetUnreadCount;
}

export interface NotificationDependencies {
    readonly notifications: NotificationRepository;
}

export function makeNotificationUseCases({ notifications }: NotificationDependencies): NotificationUseCases {
    return {
        getUnreadCount: makeGetUnreadCount(notifications),
    };
}
