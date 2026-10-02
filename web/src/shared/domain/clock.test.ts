import { describe, expect, it } from "vitest";
import { fixedClock } from "./clock";
import { asIsoInstant, instantFromDate, instantToDate } from "./instant";

describe("fixedClock", () => {
    it("always answers the same instant, as a fresh Date", () => {
        const clock = fixedClock("2026-01-15T12:00:00Z");
        const first = clock.now();
        first.setFullYear(2000);

        expect(clock.now().toISOString()).toBe("2026-01-15T12:00:00.000Z");
    });

    it("rejects a value that is not an instant", () => {
        expect(() => fixedClock("yesterday")).toThrow(RangeError);
    });
});

describe("instant", () => {
    it("round-trips through Date without losing the instant", () => {
        const instant = asIsoInstant("2026-01-15T12:00:00.000Z");
        expect(instantFromDate(instantToDate(instant))).toBe(instant);
    });

    it("rejects a value that is not an instant", () => {
        expect(() => asIsoInstant("not-a-date")).toThrow(RangeError);
    });
});
