import { fixedClock } from "@/shared/domain/clock";
import { ApiError, NetworkError } from "@/shared/domain/errors";
import { createLogger } from "@/shared/infrastructure/logging/logger";
import { beforeEach, describe, expect, it } from "vitest";
import { makeStartSession } from "../application/start-session";
import { InMemorySessionStore, ScriptedTokenIssuer, sequentialSessionIds } from "../application/testing/in-memory";
import { sessionId } from "../domain/testing/fixtures";
import { createLoginHandler, MAX_LOGIN_BODY_BYTES } from "./login-handler";
import { sessionCookie } from "./session-cookie";

const ORIGIN = "http://localhost:5173";
const clock = fixedClock("2026-01-15T12:00:00Z");

let store: InMemorySessionStore;
let issuer: ScriptedTokenIssuer;
let handle: (request: Request) => Promise<Response>;

const login = (body: string, headers: Record<string, string> = {}) =>
    handle(
        new Request(`${ORIGIN}/api/session/login`, {
            method: "POST",
            headers: { Origin: ORIGIN, "X-Pallet-Request": "1", "Content-Type": "application/json", ...headers },
            body,
        }),
    );

const credentials = JSON.stringify({ email: "ada@example.com", password: "correct horse" });
const json = async (response: Response) => (await response.json()) as Record<string, unknown>;

beforeEach(() => {
    store = new InMemorySessionStore();
    issuer = new ScriptedTokenIssuer();
    handle = createLoginHandler({
        cookie: sessionCookie(ORIGIN),
        startSession: makeStartSession({ store, issuer, ids: sequentialSessionIds("b"), clock }),
        publicOrigin: ORIGIN,
        trustedProxyHops: 0,
        clock,
        logger: createLogger("fatal"),
    });
});

describe("login handler", () => {
    it("issues the session cookie for the refresh token's lifetime", async () => {
        const response = await login(credentials);

        expect(response.status).toBe(200);
        expect(response.headers.get("Set-Cookie")).toBe(
            `pallet_session=${sessionId("b")}; Path=/; HttpOnly; SameSite=Lax; Max-Age=1800`,
        );
        expect(await json(response)).toEqual({ success: true, message: "Signed in", data: { ok: true } });
    });

    it.each([
        ["malformed JSON", "{"],
        ["a missing password", JSON.stringify({ email: "ada@example.com" })],
        ["a non-object", "[]"],
    ])("answers %s with VALIDATION_ERROR without calling identity", async (_, body) => {
        const response = await login(body);

        expect(response.status).toBe(400);
        expect(await json(response)).toMatchObject({ error: "VALIDATION_ERROR" });
        expect(issuer.loggedIn).toEqual([]);
    });

    it("places field errors on the fields", async () => {
        const response = await login(JSON.stringify({ email: "nope", password: "x" }));

        expect((await json(response)).validationErrors).toEqual([
            { field: "email", message: "Enter a valid email address" },
        ]);
    });

    it("refuses an oversized body", async () => {
        const response = await login(
            JSON.stringify({ email: "ada@example.com", password: "x".repeat(MAX_LOGIN_BODY_BYTES) }),
        );

        expect(response.status).toBe(413);
        expect(issuer.loggedIn).toEqual([]);
    });

    it("relays identity's error with its field errors, meta and Retry-After", async () => {
        issuer.nextLogin = () =>
            Promise.reject(
                new ApiError(429, "TOO_MANY_REQUESTS", "Slow down", [], { retryAfter: 30 }, "corr-identity"),
            );

        const response = await login(credentials);

        expect(response.status).toBe(429);
        expect(response.headers.get("Retry-After")).toBe("30");
        expect(response.headers.get("X-Correlation-Id")).toBe("corr-identity");
        expect(await json(response)).toMatchObject({
            error: "TOO_MANY_REQUESTS",
            message: "Slow down",
            statusCode: 429,
            meta: { retryAfter: 30 },
        });
    });

    it("answers 502 when identity can't be reached", async () => {
        issuer.nextLogin = () => Promise.reject(new NetworkError());

        const response = await login(credentials);

        expect(response.status).toBe(502);
        expect(await json(response)).toMatchObject({ error: "BAD_GATEWAY" });
    });

    it("answers 503 when the session can't be stored", async () => {
        store.create = () => Promise.reject(new Error("Redis is down"));

        const response = await login(credentials);

        expect(response.status).toBe(503);
        expect(response.headers.has("Set-Cookie")).toBe(false);
    });

    it("rejects a request without the CSRF header", async () => {
        const response = await handle(
            new Request(`${ORIGIN}/api/session/login`, {
                method: "POST",
                headers: { Origin: ORIGIN },
                body: credentials,
            }),
        );

        expect(response.status).toBe(403);
        expect(issuer.loggedIn).toEqual([]);
    });
});
