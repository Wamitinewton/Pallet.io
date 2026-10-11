import type { DeliveryId } from "@/shared/domain/ids";
import type { IsoInstant } from "@/shared/domain/instant";

/** Shown on the badge as is up to this, then as "99+". */
export const MAX_DISPLAYED_UNREAD = 99;

export const NOTIFICATIONS_PAGE_SIZE = 20;

export const READ_FILTERS = ["ALL", "UNREAD"] as const;
export type ReadFilter = (typeof READ_FILTERS)[number];
export const DEFAULT_READ_FILTER: ReadFilter = "ALL";

/** One in-app delivery to the caller. Title and body are plain text, never markup. */
export interface Notification {
    readonly id: DeliveryId;
    readonly title: string;
    readonly body: string;
    readonly read: boolean;
    /** Absent while the delivery is still being written. */
    readonly sentAt: IsoInstant | null;
    readonly readAt: IsoInstant | null;
}

export function unreadBadge(count: number): string {
    return count > MAX_DISPLAYED_UNREAD ? `${String(MAX_DISPLAYED_UNREAD)}+` : String(count);
}

export function markedRead(notification: Notification, at: IsoInstant): Notification {
    return notification.read ? notification : { ...notification, read: true, readAt: at };
}

export function decrementedUnread(count: number): number {
    return Math.max(0, count - 1);
}
