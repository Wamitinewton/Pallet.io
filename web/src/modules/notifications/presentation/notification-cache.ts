import type { DeliveryId } from "@/shared/domain/ids";
import type { IsoInstant } from "@/shared/domain/instant";
import type { Page } from "@/shared/domain/page";
import type { InfiniteData } from "@tanstack/react-query";
import { markedRead, type Notification } from "../domain/notification";

export type NotificationPages = InfiniteData<Page<Notification>>;

function mapPages(
    data: NotificationPages | undefined,
    map: (page: Page<Notification>) => Page<Notification>,
): NotificationPages | undefined {
    return data === undefined ? undefined : { ...data, pages: data.pages.map(map) };
}

function contains(data: NotificationPages | undefined, id: DeliveryId): boolean {
    return data?.pages.some((page) => page.items.some((item) => item.id === id)) ?? false;
}

/** Puts `notification` back exactly as given wherever it is loaded. */
export function withNotification(
    data: NotificationPages | undefined,
    notification: Notification,
): NotificationPages | undefined {
    if (!contains(data, notification.id)) return data;
    return mapPages(data, (page) => ({
        ...page,
        items: page.items.map((item) => (item.id === notification.id ? notification : item)),
    }));
}

export function withRead(data: NotificationPages | undefined, id: DeliveryId, at: IsoInstant) {
    if (!contains(data, id)) return data;
    return mapPages(data, (page) => ({
        ...page,
        items: page.items.map((item) => (item.id === id ? markedRead(item, at) : item)),
    }));
}

export function withAllRead(data: NotificationPages | undefined, at: IsoInstant) {
    return mapPages(data, (page) => ({ ...page, items: page.items.map((item) => markedRead(item, at)) }));
}

export function withoutNotification(data: NotificationPages | undefined, id: DeliveryId) {
    if (!contains(data, id)) return data;
    return mapPages(data, (page) => ({
        ...page,
        items: page.items.filter((item) => item.id !== id),
        totalItems: Math.max(0, page.totalItems - 1),
    }));
}

/** A list with nothing in it, as the unread list is once everything has been read. */
export function emptied(data: NotificationPages | undefined): NotificationPages | undefined {
    const first = data?.pages[0];
    if (first === undefined) return data;
    return {
        pages: [{ ...first, items: [], page: 0, totalItems: 0, totalPages: 0, isFirst: true, isLast: true }],
        pageParams: [0],
    };
}

/**
 * Every loaded notification, once each. Pages are offsets, so one that arrives between two page loads
 * pushes the last of one page onto the start of the next.
 */
export function loadedNotifications(data: NotificationPages | undefined): readonly Notification[] {
    const seen = new Set<DeliveryId>();
    const notifications: Notification[] = [];
    for (const item of data?.pages.flatMap((page) => page.items) ?? []) {
        if (seen.has(item.id)) continue;
        seen.add(item.id);
        notifications.push(item);
    }
    return notifications;
}
