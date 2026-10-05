import { ApiError, NetworkError } from "@/shared/domain/errors";
import { messageFor, NETWORK_COPY, tooManyAttemptsCopy } from "@/shared/presentation/errors/error-copy";
import { describe, expect, it } from "vitest";
import { signInErrorCopy } from "./sign-in-copy";

const apiError = (status: number, code: string, meta: Record<string, unknown> = {}) =>
    new ApiError(status, code, "Upstream message", [], meta, "corr-1");

describe("signInErrorCopy", () => {
    it.each([
        ["INVALID_CREDENTIALS", apiError(401, "INVALID_CREDENTIALS"), "Email or password is incorrect."],
        ["EMAIL_NOT_VERIFIED", apiError(403, "EMAIL_NOT_VERIFIED"), "Confirm your email first."],
        [
            "TOO_MANY_REQUESTS",
            apiError(429, "TOO_MANY_REQUESTS", { retryAfter: 30 }),
            "Too many attempts. Try again in 30 seconds.",
        ],
        [
            "TOO_MANY_REQUESTS without a retry time",
            apiError(429, "TOO_MANY_REQUESTS"),
            "Too many attempts. Try again in 60 seconds.",
        ],
    ])("words %s", (_, error, copy) => {
        expect(messageFor(error, signInErrorCopy)).toBe(copy);
    });

    it("falls back to the shared copy for transport failures", () => {
        expect(messageFor(new NetworkError(), signInErrorCopy)).toBe(NETWORK_COPY);
    });
});

describe("tooManyAttemptsCopy", () => {
    it("uses the singular for one second", () => {
        expect(tooManyAttemptsCopy(1)).toBe("Too many attempts. Try again in 1 second.");
    });
});
