import { asDeliveryId } from "@/shared/domain/ids";
import { asIsoInstant } from "@/shared/domain/instant";
import { describe, expect, it } from "vitest";
import { decrementedUnread, markedRead, unreadBadge, type Notification } from "./notification";

const unread: Notification = {
    id: asDeliveryId("5d6b1a52-3f0e-4c1e-9a7b-0f1e2d3c4b5a"),
    title: "Brian Mwangi joined Kilima Labs",
    body: "Brian accepted your invite.",
    read: false,
    sentAt: asIsoInstant("2026-01-15T11:00:00Z"),
    readAt: null,
};

describe("unreadBadge", () => {
    it.each([
        [1, "1"],
        [99, "99"],
        [100, "99+"],
        [4210, "99+"],
    ])("shows %i as %s", (count, badge) => {
        expect(unreadBadge(count)).toBe(badge);
    });
});

describe("markedRead", () => {
    const at = asIsoInstant("2026-01-15T12:00:00Z");

    it("reads an unread notification at the given instant", () => {
        expect(markedRead(unread, at)).toEqual({ ...unread, read: true, readAt: at });
    });

    it("keeps when a notification was first read", () => {
        const read = { ...unread, read: true, readAt: asIsoInstant("2026-01-15T11:30:00Z") };

        expect(markedRead(read, at)).toBe(read);
    });
});

describe("decrementedUnread", () => {
    it("never goes below zero", () => {
        expect(decrementedUnread(2)).toBe(1);
        expect(decrementedUnread(0)).toBe(0);
    });
});
