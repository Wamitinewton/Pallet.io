import { describe, expect, it } from "vitest";
import { ApiError, NetworkError, UnexpectedResponseError } from "./errors";
import { classifyRequestFailure, DEFAULT_RETRY_AFTER_SECONDS, secondsUntil } from "./request-failure";

const NOW = new Date("2026-01-15T12:00:00Z");
const apiError = (status: number, code: string, meta: Record<string, unknown> = {}) =>
    new ApiError(status, code, "message", [], meta, undefined);

describe("classifyRequestFailure", () => {
    it.each([
        [apiError(400, "VALIDATION_ERROR"), "rejected"],
        [apiError(409, "CONFLICT"), "rejected"],
        [apiError(503, "SERVICE_UNAVAILABLE"), "unavailable"],
        [new NetworkError(), "unavailable"],
        [new UnexpectedResponseError(502, "Bad gateway", undefined), "unavailable"],
        [new Error("bug"), "unavailable"],
    ])("classifies %o as %s", (error, kind) => {
        expect(classifyRequestFailure(error, NOW).kind).toBe(kind);
    });

    it("sets the retry deadline from meta.retryAfter", () => {
        expect(classifyRequestFailure(apiError(429, "TOO_MANY_REQUESTS", { retryAfter: 30 }), NOW)).toMatchObject({
            kind: "rate-limited",
            retryAfterSeconds: 30,
            retryAt: new Date("2026-01-15T12:00:30Z"),
        });
    });

    it("waits a minute when the limit names no retry time", () => {
        expect(classifyRequestFailure(apiError(429, "TOO_MANY_REQUESTS"), NOW)).toMatchObject({
            kind: "rate-limited",
            retryAfterSeconds: DEFAULT_RETRY_AFTER_SECONDS,
        });
    });
});

describe("secondsUntil", () => {
    it("rounds partial seconds up and never goes below zero", () => {
        const deadline = new Date("2026-01-15T12:00:30Z");

        expect(secondsUntil(deadline, NOW)).toBe(30);
        expect(secondsUntil(deadline, new Date("2026-01-15T12:00:29.100Z"))).toBe(1);
        expect(secondsUntil(deadline, new Date("2026-01-15T12:00:30Z"))).toBe(0);
        expect(secondsUntil(deadline, new Date("2026-01-15T12:01:00Z"))).toBe(0);
    });
});
