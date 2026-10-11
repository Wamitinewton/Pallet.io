import type { Page } from "@/shared/domain/page";
import { NOTIFICATIONS_PAGE_SIZE, type Notification, type ReadFilter } from "../domain/notification";
import type { NotificationRepository } from "./ports";

/** `page` counts from 0. */
export type ListNotifications = (filter: ReadFilter, page: number) => Promise<Page<Notification>>;

export function makeListNotifications(notifications: NotificationRepository): ListNotifications {
    return (filter, page) => notifications.list(filter, { page, size: NOTIFICATIONS_PAGE_SIZE });
}
