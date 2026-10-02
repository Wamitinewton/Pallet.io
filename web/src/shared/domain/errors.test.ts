import { describe, expect, it } from "vitest";
import { ApiError, correlationIdOf, isAppError, NetworkError, UnexpectedResponseError } from "./errors";

const apiError = (meta: Record<string, unknown>) =>
    new ApiError(429, "TOO_MANY_REQUESTS", "Slow down", [], meta, "c-1");

describe("ApiError", () => {
    it("reads retryAfter from meta as whole seconds", () => {
        expect(apiError({ retryAfter: 30 }).retryAfterSeconds()).toBe(30);
        expect(apiError({ retryAfter: "12.2" }).retryAfterSeconds()).toBe(13);
    });

    it("has no retry hint when meta lacks a usable retryAfter", () => {
        expect(apiError({}).retryAfterSeconds()).toBeUndefined();
        expect(apiError({ retryAfter: "soon" }).retryAfterSeconds()).toBeUndefined();
        expect(apiError({ retryAfter: -1 }).retryAfterSeconds()).toBeUndefined();
    });

    it("matches by code", () => {
        expect(apiError({}).is("TOO_MANY_REQUESTS")).toBe(true);
        expect(apiError({}).is("NOT_FOUND")).toBe(false);
    });
});

describe("error helpers", () => {
    it("recognises the four kinds and nothing else", () => {
        expect(isAppError(new NetworkError())).toBe(true);
        expect(isAppError(new Error("boom"))).toBe(false);
    });

    it("finds the correlation id where one exists", () => {
        expect(correlationIdOf(apiError({}))).toBe("c-1");
        expect(correlationIdOf(new UnexpectedResponseError(502, "Bad gateway", "c-2"))).toBe("c-2");
        expect(correlationIdOf(new NetworkError())).toBeUndefined();
    });
});
