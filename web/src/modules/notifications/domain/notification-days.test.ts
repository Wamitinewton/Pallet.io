import { asDeliveryId } from "@/shared/domain/ids";
import { asIsoInstant } from "@/shared/domain/instant";
import { describe, expect, it } from "vitest";
import type { Notification } from "./notification";
import { groupByDay } from "./notification-days";

/** A Wednesday, just after midnight in Nairobi and still Tuesday evening in UTC. */
const NOW = new Date("2026-09-29T21:30:00Z");

function sentAt(iso: string | null, title = iso ?? "undated"): Notification {
    return {
        id: asDeliveryId(crypto.randomUUID()),
        title,
        body: "",
        read: false,
        sentAt: iso === null ? null : asIsoInstant(iso),
        readAt: null,
    };
}

const days = (notifications: Notification[], timeZone: string) =>
    groupByDay(notifications, NOW, timeZone).map((group) => ({
        day: group.day,
        titles: group.notifications.map((notification) => notification.title),
    }));

describe("groupByDay", () => {
    it("groups by the reader's calendar day, not UTC's", () => {
        const notifications = [sentAt("2026-09-29T21:10:00Z"), sentAt("2026-09-29T09:00:00Z")];

        expect(days(notifications, "Africa/Nairobi")).toEqual([
            { day: { kind: "today" }, titles: ["2026-09-29T21:10:00Z"] },
            { day: { kind: "yesterday" }, titles: ["2026-09-29T09:00:00Z"] },
        ]);
        expect(days(notifications, "UTC")).toEqual([
            { day: { kind: "today" }, titles: ["2026-09-29T21:10:00Z", "2026-09-29T09:00:00Z"] },
        ]);
    });

    it("names the rest of the week, then each month, with the year once it isn't this one", () => {
        const notifications = [
            sentAt("2026-09-25T09:00:00Z"),
            sentAt("2026-09-24T09:00:00Z"),
            sentAt("2026-09-16T09:00:00Z"),
            sentAt("2026-03-12T09:00:00Z"),
            sentAt("2025-12-31T09:00:00Z"),
        ];

        expect(days(notifications, "Africa/Nairobi")).toEqual([
            { day: { kind: "this-week" }, titles: ["2026-09-25T09:00:00Z", "2026-09-24T09:00:00Z"] },
            { day: { kind: "month", year: 2026, month: 9, thisYear: true }, titles: ["2026-09-16T09:00:00Z"] },
            { day: { kind: "month", year: 2026, month: 3, thisYear: true }, titles: ["2026-03-12T09:00:00Z"] },
            { day: { kind: "month", year: 2025, month: 12, thisYear: false }, titles: ["2025-12-31T09:00:00Z"] },
        ]);
    });

    it("keeps an undated delivery with the one before it", () => {
        const notifications = [sentAt(null, "first"), sentAt("2026-09-20T09:00:00Z"), sentAt(null, "later")];

        expect(days(notifications, "Africa/Nairobi")).toEqual([
            { day: { kind: "today" }, titles: ["first"] },
            {
                day: { kind: "month", year: 2026, month: 9, thisYear: true },
                titles: ["2026-09-20T09:00:00Z", "later"],
            },
        ]);
    });

    it("counts a delivery stamped slightly ahead of the reader's clock as today", () => {
        expect(days([sentAt("2026-09-29T21:31:00Z")], "Africa/Nairobi")).toEqual([
            { day: { kind: "today" }, titles: ["2026-09-29T21:31:00Z"] },
        ]);
    });
});
