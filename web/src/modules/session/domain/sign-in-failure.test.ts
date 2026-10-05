import { ApiError, NetworkError, UnexpectedResponseError } from "@/shared/domain/errors";
import { describe, expect, it } from "vitest";
import { classifySignInFailure } from "./sign-in-failure";

const NOW = new Date("2026-01-15T12:00:00Z");
const apiError = (status: number, code: string, meta: Record<string, unknown> = {}) =>
    new ApiError(status, code, "message", [], meta, undefined);

describe("classifySignInFailure", () => {
    it.each([
        [apiError(401, "INVALID_CREDENTIALS"), "invalid-credentials"],
        [apiError(403, "EMAIL_NOT_VERIFIED"), "email-not-verified"],
        [apiError(400, "VALIDATION_ERROR"), "rejected"],
        [apiError(503, "SERVICE_UNAVAILABLE"), "unavailable"],
        [new NetworkError(), "unavailable"],
        [new UnexpectedResponseError(502, "Bad gateway", undefined), "unavailable"],
        [new Error("bug"), "unavailable"],
    ])("classifies %o as %s", (error, kind) => {
        expect(classifySignInFailure(error, NOW).kind).toBe(kind);
    });

    it("sets the retry deadline from meta.retryAfter", () => {
        const failure = classifySignInFailure(apiError(429, "TOO_MANY_REQUESTS", { retryAfter: 30 }), NOW);

        expect(failure).toMatchObject({
            kind: "rate-limited",
            retryAfterSeconds: 30,
            retryAt: new Date("2026-01-15T12:00:30Z"),
        });
    });
});
