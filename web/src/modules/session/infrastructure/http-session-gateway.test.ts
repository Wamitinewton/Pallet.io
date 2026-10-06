import { ApiError, NetworkError, SessionExpiredError, UnexpectedResponseError } from "@/shared/domain/errors";
import { describe, expect, it, vi } from "vitest";
import { httpSessionGateway } from "./http-session-gateway";

const errorBody = (status: number, code: string, meta?: Record<string, unknown>) =>
    Response.json({ success: false, message: "No", error: code, statusCode: status, meta }, { status });

function recording(reply: () => Promise<Response>) {
    const send = vi.fn<typeof fetch>(reply);
    const requestAt = (index: number) => {
        const [url, init] = send.mock.calls[index] ?? [];
        return { url, init, headers: new Headers(init?.headers) };
    };
    return { send, requestAt };
}

describe("httpSessionGateway", () => {
    it("posts the credentials to the login route with the CSRF header", async () => {
        const { send, requestAt } = recording(() => Promise.resolve(Response.json({ success: true })));

        await httpSessionGateway({ fetch: send }).signIn({ email: "ada@example.com", password: "pw" });

        const { url, init, headers } = requestAt(0);
        expect(url).toBe("/api/session/login");
        expect(init).toMatchObject({ method: "POST", credentials: "same-origin", cache: "no-store" });
        expect(init?.body).toBe(JSON.stringify({ email: "ada@example.com", password: "pw" }));
        expect(headers.get("X-Pallet-Request")).toBe("1");
        expect(headers.get("Content-Type")).toBe("application/json");
    });

    it("posts an empty logout to the configured base URL", async () => {
        const { send, requestAt } = recording(() => Promise.resolve(new Response(null, { status: 204 })));

        await httpSessionGateway({ fetch: send, baseUrl: "http://localhost/api/session" }).signOut();

        const { url, init, headers } = requestAt(0);
        expect(url).toBe("http://localhost/api/session/logout");
        expect(init?.body).toBeUndefined();
        expect(headers.has("Content-Type")).toBe(false);
    });

    it("maps an error envelope to an ApiError with its retry time", async () => {
        const { send } = recording(() => Promise.resolve(errorBody(429, "TOO_MANY_REQUESTS", { retryAfter: 30 })));

        const failure = await httpSessionGateway({ fetch: send })
            .signIn({ email: "ada@example.com", password: "pw" })
            .catch((error: unknown) => error);

        expect(failure).toBeInstanceOf(ApiError);
        expect((failure as ApiError).retryAfterSeconds()).toBe(30);
    });

    it("maps a failed fetch to a NetworkError", async () => {
        const { send } = recording(() => Promise.reject(new TypeError("Failed to fetch")));

        await expect(httpSessionGateway({ fetch: send }).signOut()).rejects.toBeInstanceOf(NetworkError);
    });

    it("reads the session summary from the session route", async () => {
        const { send, requestAt } = recording(() =>
            Promise.resolve(
                Response.json({
                    success: true,
                    message: "Current session",
                    data: {
                        userId: "user-1",
                        email: "ada@example.com",
                        keycloakSessionId: "kc-1",
                        accessExpiresAt: "2026-01-15T12:05:00.000Z",
                    },
                }),
            ),
        );

        const summary = await httpSessionGateway({ fetch: send }).summary();

        const { url, init, headers } = requestAt(0);
        expect(url).toBe("/api/session");
        expect(init).toMatchObject({ method: "GET", credentials: "same-origin", cache: "no-store" });
        expect(headers.get("X-Pallet-Request")).toBe("1");
        expect(summary).toEqual({
            userId: "user-1",
            email: "ada@example.com",
            keycloakSessionId: "kc-1",
            accessExpiresAt: "2026-01-15T12:05:00.000Z",
        });
    });

    it("reads a summary whose token named no Keycloak session", async () => {
        const { send } = recording(() =>
            Promise.resolve(
                Response.json({
                    success: true,
                    message: "Current session",
                    data: { userId: "user-1", email: "ada@example.com", accessExpiresAt: "2026-01-15T12:05:00.000Z" },
                }),
            ),
        );

        expect((await httpSessionGateway({ fetch: send }).summary()).keycloakSessionId).toBeUndefined();
    });

    it("raises an ended session as SessionExpiredError", async () => {
        const { send } = recording(() => Promise.resolve(errorBody(401, "SESSION_EXPIRED")));

        await expect(httpSessionGateway({ fetch: send }).summary()).rejects.toBeInstanceOf(SessionExpiredError);
    });

    it("refuses a summary that broke its contract", async () => {
        const { send } = recording(() => Promise.resolve(Response.json({ success: true, data: { email: "x" } })));

        await expect(httpSessionGateway({ fetch: send }).summary()).rejects.toBeInstanceOf(UnexpectedResponseError);
    });

    it("posts the password to the reauthenticate route with the CSRF header", async () => {
        const { send, requestAt } = recording(() => Promise.resolve(Response.json({ success: true })));

        await httpSessionGateway({ fetch: send }).reauthenticate({ password: "correct horse" });

        const { url, init, headers } = requestAt(0);
        expect(url).toBe("/api/session/reauthenticate");
        expect(init).toMatchObject({ method: "POST", credentials: "same-origin", cache: "no-store" });
        expect(init?.body).toBe(JSON.stringify({ password: "correct horse" }));
        expect(headers.get("X-Pallet-Request")).toBe("1");
    });

    it("raises a wrong password as an ApiError", async () => {
        const { send } = recording(() => Promise.resolve(errorBody(401, "INVALID_CREDENTIALS")));

        await expect(httpSessionGateway({ fetch: send }).reauthenticate({ password: "nope" })).rejects.toMatchObject({
            code: "INVALID_CREDENTIALS",
        });
    });
});
