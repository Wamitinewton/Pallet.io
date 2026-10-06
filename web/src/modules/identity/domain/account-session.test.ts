import { ApiError } from "@/shared/domain/errors";
import { asIsoInstant } from "@/shared/domain/instant";
import { describe, expect, it } from "vitest";
import {
    asAccountSessionId,
    byRecentActivity,
    isAlreadyEnded,
    isCurrent,
    otherSessions,
    withoutSession,
    type AccountSession,
} from "./account-session";

function session(id: string, lastAccessedAt: string, startedAt = "2026-01-01T08:00:00Z"): AccountSession {
    return {
        id: asAccountSessionId(id),
        ipAddress: "197.237.14.82",
        startedAt: asIsoInstant(startedAt),
        lastAccessedAt: asIsoInstant(lastAccessedAt),
    };
}

const here = session("kc-here", "2026-01-15T11:59:00Z");
const laptop = session("kc-laptop", "2026-01-15T09:00:00Z");
const phone = session("kc-phone", "2026-01-09T10:15:00Z");

describe("isCurrent", () => {
    it("matches the session this browser is signed in with", () => {
        expect(isCurrent(here, "kc-here")).toBe(true);
        expect(isCurrent(laptop, "kc-here")).toBe(false);
    });

    it("marks nothing current when the token named no session", () => {
        expect(isCurrent(here, undefined)).toBe(false);
    });
});

describe("byRecentActivity", () => {
    it("puts the most recently active first", () => {
        expect(byRecentActivity([phone, here, laptop]).map(({ id }) => id)).toEqual([
            "kc-here",
            "kc-laptop",
            "kc-phone",
        ]);
    });

    it("breaks a tie by the later start", () => {
        const older = session("kc-older", "2026-01-15T09:00:00Z", "2026-01-02T08:00:00Z");
        const newer = session("kc-newer", "2026-01-15T09:00:00Z", "2026-01-05T08:00:00Z");

        expect(byRecentActivity([older, newer]).map(({ id }) => id)).toEqual(["kc-newer", "kc-older"]);
    });
});

describe("otherSessions and withoutSession", () => {
    it("leave out this device, or the named session", () => {
        expect(otherSessions([here, laptop, phone], "kc-here")).toEqual([laptop, phone]);
        expect(withoutSession([here, laptop, phone], asAccountSessionId("kc-laptop"))).toEqual([here, phone]);
    });
});

describe("isAlreadyEnded", () => {
    it("reads 404 SESSION_NOT_FOUND as already ended", () => {
        expect(isAlreadyEnded(new ApiError(404, "SESSION_NOT_FOUND", "Gone", [], {}, undefined))).toBe(true);
        expect(isAlreadyEnded(new ApiError(404, "NOT_FOUND", "Gone", [], {}, undefined))).toBe(false);
        expect(isAlreadyEnded(new Error("SESSION_NOT_FOUND"))).toBe(false);
    });
});
