import { ApiError, NetworkError, SessionExpiredError, UnexpectedResponseError } from "@/shared/domain/errors";
import { describe, expect, it } from "vitest";
import { GENERIC_COPY, messageFor, NETWORK_COPY, SESSION_EXPIRED_COPY } from "./error-copy";

const apiError = (status: number, code: string, message: string, meta: Record<string, unknown> = {}) =>
    new ApiError(status, code, message, [], meta, "corr-9");

describe("messageFor", () => {
    it("prefers the screen's copy for the code", () => {
        const error = apiError(409, "SLUG_TAKEN", "Slug already in use");

        expect(messageFor(error, { SLUG_TAKEN: "That URL is taken." })).toBe("That URL is taken.");
    });

    it("uses shared copy for platform codes, including the retry hint", () => {
        expect(messageFor(apiError(429, "TOO_MANY_REQUESTS", "Too many", { retryAfter: 1 }))).toBe(
            "Too many requests. Try again in 1 second.",
        );
        expect(messageFor(apiError(429, "TOO_MANY_REQUESTS", "Too many"))).toBe(
            "Too many requests. Wait a moment and try again.",
        );
    });

    it("falls back to the backend's client-safe message", () => {
        expect(messageFor(apiError(409, "INVITE_ALREADY_PENDING", "An invite is already pending"))).toBe(
            "An invite is already pending",
        );
    });

    it("never shows a 5xx message, only the generic line with the reference", () => {
        expect(messageFor(apiError(500, "INTERNAL_ERROR", "NullPointerException"))).toBe(
            `${GENERIC_COPY} Reference: corr-9`,
        );
        expect(messageFor(new UnexpectedResponseError(502, "Bad gateway", "corr-7"), {}, { reference: false })).toBe(
            GENERIC_COPY,
        );
    });

    it("has fixed copy for the transport-level kinds", () => {
        expect(messageFor(new SessionExpiredError())).toBe(SESSION_EXPIRED_COPY);
        expect(messageFor(new NetworkError())).toBe(NETWORK_COPY);
        expect(messageFor(new Error("bug"))).toBe(GENERIC_COPY);
    });
});
