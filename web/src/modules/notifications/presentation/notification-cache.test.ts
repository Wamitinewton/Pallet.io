import { asDeliveryId } from "@/shared/domain/ids";
import { asIsoInstant } from "@/shared/domain/instant";
import type { Page } from "@/shared/domain/page";
import { describe, expect, it } from "vitest";
import type { Notification } from "../domain/notification";
import {
    emptied,
    loadedNotifications,
    withAllRead,
    withNotification,
    withoutNotification,
    withRead,
    type NotificationPages,
} from "./notification-cache";

const AT = asIsoInstant("2026-01-15T12:00:00Z");

function notification(id: string, read = false): Notification {
    return {
        id: asDeliveryId(id),
        title: id,
        body: "",
        read,
        sentAt: asIsoInstant("2026-01-15T11:00:00Z"),
        readAt: read ? asIsoInstant("2026-01-15T11:30:00Z") : null,
    };
}

function page(items: Notification[], number: number, totalItems: number): Page<Notification> {
    return {
        items,
        page: number,
        size: 2,
        totalItems,
        totalPages: Math.ceil(totalItems / 2),
        isFirst: number === 0,
        isLast: (number + 1) * 2 >= totalItems,
    };
}

const A = notification("a");
const B = notification("b");
const C = notification("c", true);
const pages: NotificationPages = { pages: [page([A, B], 0, 3), page([C], 1, 3)], pageParams: [0, 1] };
const ids = (data: NotificationPages | undefined) => loadedNotifications(data).map((item) => item.id);

describe("notification cache", () => {
    it("reads one wherever it is loaded, leaving the others alone", () => {
        const next = withRead(pages, B.id, AT);

        expect(loadedNotifications(next).map((item) => [item.id, item.read])).toEqual([
            ["a", false],
            ["b", true],
            ["c", true],
        ]);
        expect(loadedNotifications(next)[1]?.readAt).toBe(AT);
    });

    it("leaves a list that doesn't hold the notification as it is", () => {
        expect(withRead(pages, asDeliveryId("z"), AT)).toBe(pages);
        expect(withoutNotification(pages, asDeliveryId("z"))).toBe(pages);
        expect(withRead(undefined, A.id, AT)).toBeUndefined();
    });

    it("puts one back exactly as it was", () => {
        expect(withNotification(withRead(pages, A.id, AT), A)).toEqual(pages);
    });

    it("reads every loaded notification, keeping when the read ones were read", () => {
        const next = loadedNotifications(withAllRead(pages, AT));

        expect(next.every((item) => item.read)).toBe(true);
        expect(next[2]?.readAt).toBe(C.readAt);
    });

    it("drops one and counts one fewer on every page", () => {
        const next = withoutNotification(pages, A.id);

        expect(ids(next)).toEqual(["b", "c"]);
        expect(next?.pages.map((loaded) => loaded.totalItems)).toEqual([2, 2]);
    });

    it("empties a list down to one empty first page", () => {
        expect(emptied(pages)).toEqual({ pages: [page([], 0, 0)], pageParams: [0] });
    });

    it("lists each loaded notification once when a new one pushed another onto the next page", () => {
        const shifted: NotificationPages = { pages: [page([A, B], 0, 4), page([B, C], 1, 4)], pageParams: [0, 1] };

        expect(ids(shifted)).toEqual(["a", "b", "c"]);
    });
});
