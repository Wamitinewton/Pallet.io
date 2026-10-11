import type { DeliveryId } from "@/shared/domain/ids";
import type { Page, PageRequest } from "@/shared/domain/page";
import type { Notification, ReadFilter } from "../domain/notification";

/** The caller's own notifications: every call is scoped by the signed-in person, never by an organization. */
export interface NotificationRepository {
    /** Newest first. */
    list(filter: ReadFilter, page: PageRequest): Promise<Page<Notification>>;
    markRead(id: DeliveryId): Promise<void>;
    markAllRead(): Promise<void>;
    unreadCount(): Promise<number>;
}
