import type { NotificationRepository } from "./ports";

export type MarkAllRead = () => Promise<void>;

export function makeMarkAllRead(notifications: NotificationRepository): MarkAllRead {
    return () => notifications.markAllRead();
}
