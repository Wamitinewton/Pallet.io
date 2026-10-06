import { describe, expect, it } from "vitest";
import { makeGetUnreadCount } from "./get-unread-count";

describe("getUnreadCount", () => {
    it("reads the caller's unread count", async () => {
        expect(await makeGetUnreadCount({ unreadCount: () => Promise.resolve(3) })()).toBe(3);
    });
});
