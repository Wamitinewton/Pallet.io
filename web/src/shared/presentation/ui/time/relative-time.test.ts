import { describe, expect, it } from "vitest";
import { formatRelativeTime } from "./relative-time";

const now = new Date("2026-10-02T12:00:00Z");
const ago = (seconds: number) => new Date(now.getTime() - seconds * 1000);

describe("formatRelativeTime", () => {
    it.each([
        [10, "just now"],
        [50, "1 minute ago"],
        [3 * 60, "3 minutes ago"],
        [2 * 60 * 60, "2 hours ago"],
        [24 * 60 * 60, "yesterday"],
        [3 * 24 * 60 * 60, "3 days ago"],
        [14 * 24 * 60 * 60, "2 weeks ago"],
        [400 * 24 * 60 * 60, "last year"],
    ])("%i seconds ago reads %s", (seconds, expected) => {
        expect(formatRelativeTime(ago(seconds), now)).toBe(expected);
    });

    it("reads future times forward", () => {
        expect(formatRelativeTime(ago(-2 * 24 * 60 * 60), now)).toBe("in 2 days");
    });
});
