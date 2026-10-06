import type { NotificationRepository } from "./ports";

export type GetUnreadCount = () => Promise<number>;

export function makeGetUnreadCount(notifications: NotificationRepository): GetUnreadCount {
    return () => notifications.unreadCount();
}
