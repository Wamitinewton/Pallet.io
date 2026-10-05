import { ApiError, NetworkError } from "@/shared/domain/errors";
import { describe, expect, it } from "vitest";
import { classifyVerificationFailure } from "./verification-failure";

const NOW = new Date("2026-01-15T12:00:00Z");
const apiError = (status: number, code: string) => new ApiError(status, code, "message", [], {}, undefined);

describe("classifyVerificationFailure", () => {
    it.each([
        [apiError(400, "INVALID_TOKEN"), "wrong-code"],
        [apiError(400, "VALIDATION_ERROR"), "rejected"],
        [apiError(429, "TOO_MANY_REQUESTS"), "rate-limited"],
        [apiError(503, "SERVICE_UNAVAILABLE"), "unavailable"],
        [new NetworkError(), "unavailable"],
    ])("classifies %o as %s", (error, kind) => {
        expect(classifyVerificationFailure(error, NOW).kind).toBe(kind);
    });
});
