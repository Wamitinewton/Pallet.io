import { describe, expect, it } from "vitest";
import { unreadBadge } from "./notification";

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
