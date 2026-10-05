import { ApiError, NetworkError } from "@/shared/domain/errors";
import { describe, expect, it } from "vitest";
import { classifySignupFailure, isTakenConflict, SignupConflictError } from "./signup-failure";

const NOW = new Date("2026-01-15T12:00:00Z");
const apiError = (status: number, code: string) => new ApiError(status, code, "message", [], {}, undefined);

describe("isTakenConflict", () => {
    it.each([
        [apiError(409, "CONFLICT"), true],
        [apiError(409, "IDEMPOTENCY_KEY_REUSE"), false],
        [apiError(400, "CONFLICT"), false],
        [new NetworkError(), false],
    ])("%o is a taken conflict: %s", (error, taken) => {
        expect(isTakenConflict(error)).toBe(taken);
    });
});

describe("classifySignupFailure", () => {
    it.each(["slug", "email", undefined] as const)("reports a conflict on %s as taken", (field) => {
        expect(classifySignupFailure(new SignupConflictError(field), NOW)).toEqual({ kind: "taken", field });
    });

    it.each([
        [apiError(400, "VALIDATION_ERROR"), "rejected"],
        [apiError(409, "IDEMPOTENCY_KEY_REUSE"), "rejected"],
        [apiError(429, "TOO_MANY_REQUESTS"), "rate-limited"],
        [apiError(500, "INTERNAL_ERROR"), "unavailable"],
        [new NetworkError(), "unavailable"],
    ])("classifies %o as %s", (error, kind) => {
        expect(classifySignupFailure(error, NOW).kind).toBe(kind);
    });
});
