import { ApiError, NetworkError, SessionExpiredError, UnexpectedResponseError } from "@/shared/domain/errors";
import { describe, expect, it } from "vitest";
import { errorFromFetchFailure, errorFromResponse } from "./error-mapping";

function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json", ...headers },
    });
}

function errorResponse(status: number, error: string, extra: Record<string, unknown> = {}) {
    return {
        success: false,
        message: "Request failed",
        error,
        statusCode: status,
        timestamp: "2026-01-15T12:00:00Z",
        path: "/api/v1/org-team/orgs",
        ...extra,
    };
}

describe("errorFromResponse", () => {
    it("maps an ErrorResponse with validation errors and meta to an ApiError carrying both", async () => {
        const response = json(
            400,
            errorResponse(400, "VALIDATION_ERROR", {
                message: "Validation failed",
                validationErrors: [
                    { field: "name", message: "must not be blank" },
                    { field: "slug", message: "must be a DNS label" },
                ],
                meta: { retryAfter: 30, attempt: 2 },
            }),
            { "X-Correlation-Id": "corr-1" },
        );

        const error = await errorFromResponse(response);

        expect(error).toBeInstanceOf(ApiError);
        const apiError = error as ApiError;
        expect(apiError).toMatchObject({
            status: 400,
            code: "VALIDATION_ERROR",
            message: "Validation failed",
            correlationId: "corr-1",
            fieldErrors: [
                { field: "name", message: "must not be blank" },
                { field: "slug", message: "must be a DNS label" },
            ],
        });
        expect(apiError.meta).toEqual({ retryAfter: 30, attempt: 2 });
        expect(apiError.retryAfterSeconds()).toBe(30);
    });

    it("takes the retry hint from Retry-After when meta has none", async () => {
        const error = await errorFromResponse(
            json(429, errorResponse(429, "TOO_MANY_REQUESTS"), { "Retry-After": "7" }),
        );

        expect((error as ApiError).retryAfterSeconds()).toBe(7);
    });

    it("prefers the meta retry hint over the header", async () => {
        const body = errorResponse(429, "TOO_MANY_REQUESTS", { meta: { retryAfter: 40 } });
        const error = await errorFromResponse(json(429, body, { "Retry-After": "7" }));

        expect((error as ApiError).retryAfterSeconds()).toBe(40);
    });

    it("maps the BFF's 401 SESSION_EXPIRED to SessionExpiredError", async () => {
        const error = await errorFromResponse(json(401, errorResponse(401, "SESSION_EXPIRED")));

        expect(error).toBeInstanceOf(SessionExpiredError);
    });

    it("keeps any other 401 as an ApiError so a wrong password never signs anyone out", async () => {
        const error = await errorFromResponse(json(401, errorResponse(401, "INVALID_CREDENTIALS")));

        expect(error).toBeInstanceOf(ApiError);
        expect((error as ApiError).code).toBe("INVALID_CREDENTIALS");
    });

    it("maps an HTML 502 from a proxy to UnexpectedResponseError with the status", async () => {
        const response = new Response("<html><body>Bad Gateway</body></html>", {
            status: 502,
            headers: { "Content-Type": "text/html" },
        });

        const error = await errorFromResponse(response);

        expect(error).toBeInstanceOf(UnexpectedResponseError);
        expect((error as UnexpectedResponseError).status).toBe(502);
    });

    it("maps JSON that is not an ErrorResponse to UnexpectedResponseError", async () => {
        const error = await errorFromResponse(json(500, { timestamp: "now", status: 500, error: "Internal" }));

        expect(error).toBeInstanceOf(UnexpectedResponseError);
    });
});

describe("errorFromFetchFailure", () => {
    it("maps a rejected fetch to NetworkError and keeps the cause", () => {
        const cause = new TypeError("fetch failed");

        const error = errorFromFetchFailure(cause);

        expect(error).toBeInstanceOf(NetworkError);
        expect(error.cause).toBe(cause);
    });

    it("lets a cancellation through untouched", () => {
        const abort = new DOMException("The operation was aborted", "AbortError");

        expect(errorFromFetchFailure(abort)).toBe(abort);
    });

    it("maps a timeout to NetworkError", () => {
        expect(errorFromFetchFailure(new DOMException("Timed out", "TimeoutError"))).toBeInstanceOf(NetworkError);
    });
});
