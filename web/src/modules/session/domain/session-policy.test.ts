import { fixedClock } from "@/shared/domain/clock";
import { asIsoInstant } from "@/shared/domain/instant";
import { describe, expect, it } from "vitest";
import { hasValidAccessToken, isRefreshable, needsRefresh, remainingLifetimeSeconds } from "./session-policy";
import { aSession } from "./testing/fixtures";

const session = aSession({
    accessExpiresAt: asIsoInstant("2026-01-15T12:05:00.000Z"),
    refreshExpiresAt: asIsoInstant("2026-01-15T12:30:00.000Z"),
});

describe("needsRefresh", () => {
    it.each([
        ["2026-01-15T12:04:29.999Z", false],
        ["2026-01-15T12:04:30.000Z", true],
        ["2026-01-15T12:04:59.999Z", true],
        ["2026-01-15T12:06:00.000Z", true],
    ])("at %s is %s", (now, expected) => {
        expect(needsRefresh(session, fixedClock(now))).toBe(expected);
    });
});

describe("hasValidAccessToken", () => {
    it.each([
        ["2026-01-15T12:04:59.999Z", true],
        ["2026-01-15T12:05:00.000Z", false],
    ])("at %s is %s", (now, expected) => {
        expect(hasValidAccessToken(session, fixedClock(now))).toBe(expected);
    });
});

describe("isRefreshable", () => {
    it.each([
        ["2026-01-15T12:29:59.999Z", true],
        ["2026-01-15T12:30:00.000Z", false],
    ])("at %s is %s", (now, expected) => {
        expect(isRefreshable(session, fixedClock(now))).toBe(expected);
    });
});

describe("remainingLifetimeSeconds", () => {
    it("counts whole seconds until the refresh token expires and never goes negative", () => {
        expect(remainingLifetimeSeconds(session, fixedClock("2026-01-15T12:00:00.500Z"))).toBe(1799);
        expect(remainingLifetimeSeconds(session, fixedClock("2026-01-15T13:00:00.000Z"))).toBe(0);
    });
});
