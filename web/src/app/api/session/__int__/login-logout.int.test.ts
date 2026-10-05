import { sessionRuntime, type SessionRuntime } from "@/composition/session";
import { openSession, parseSessionId } from "@/modules/session/domain/session";
import { issuedTokens, sessionId, USER_ID } from "@/modules/session/domain/testing/fixtures";
import { lazyRedisConnection } from "@/modules/session/infrastructure/redis-client";
import { createLogger } from "@/shared/infrastructure/logging/logger";
import { fakeJwt } from "@/test/jwt";
import { error, ok } from "@/test/msw/envelopes";
import { gatewayUrl, server, TEST_ORIGIN } from "@/test/msw/server";
import { connectTestRedis, type TestRedis } from "@/test/redis";
import { testServerEnvVars } from "@/test/session-env";
import { http, HttpResponse } from "msw";
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import * as loginRoute from "../login/route";
import * as logoutRoute from "../logout/route";

const OLD_SESSION = sessionId("a");
const ACCESS_TOKEN = fakeJwt({ sub: USER_ID, email: "ada@example.com", sid: "kc-new" });
const CREDENTIALS = { email: "ada@example.com", password: "correct horse battery" };

let redis: TestRedis;
let runtime: SessionRuntime;

interface Seen {
    readonly headers: Headers;
    readonly body: unknown;
}

const browserWrite = (headers: Record<string, string> = {}): Record<string, string> => ({
    Origin: TEST_ORIGIN,
    "X-Pallet-Request": "1",
    "Content-Type": "application/json",
    "X-Forwarded-For": "203.0.113.7",
    ...headers,
});

const login = (headers: Record<string, string> = {}, body: unknown = CREDENTIALS) =>
    loginRoute.POST(
        new Request(`${TEST_ORIGIN}/api/session/login`, {
            method: "POST",
            headers: browserWrite(headers),
            body: JSON.stringify(body),
        }),
    );

const logout = (headers: Record<string, string> = {}) =>
    logoutRoute.POST(
        new Request(`${TEST_ORIGIN}/api/session/logout`, { method: "POST", headers: browserWrite(headers) }),
    );

function identity(path: string, reply: () => Response) {
    const seen: Seen[] = [];
    server.use(
        http.post(gatewayUrl(`/identity${path}`), async ({ request }) => {
            seen.push({ headers: request.headers, body: await request.json() });
            return reply();
        }),
    );
    return seen;
}

const issuedByIdentity = () =>
    ok({ accessToken: ACCESS_TOKEN, expiresIn: 300, refreshToken: "refresh-new", refreshExpiresIn: 1800 });

const cookieIdOf = (response: Response) => {
    const match = /^pallet_session=([^;]*);/.exec(response.headers.get("Set-Cookie") ?? "");
    return match?.[1] === undefined ? undefined : parseSessionId(match[1]);
};

async function seedOldSession(): Promise<void> {
    await runtime.store.create(
        openSession(
            OLD_SESSION,
            issuedTokens({ accessToken: "access-old", refreshToken: "refresh-old" }),
            "ada@example.com",
            new Date(),
        ),
    );
}

beforeAll(async () => {
    redis = await connectTestRedis();
    for (const [name, value] of Object.entries(testServerEnvVars(redis.url))) vi.stubEnv(name, value);
    runtime = sessionRuntime();
});

afterAll(async () => {
    const shared = await lazyRedisConnection(redis.url, createLogger("fatal"))();
    shared.destroy();
    redis.close();
});

beforeEach(async () => {
    await redis.client.flushAll();
});

describe("POST /api/session/login", () => {
    it("creates a session and sets its cookie, with no token in the body", async () => {
        const seen = identity("/auth/login", issuedByIdentity);

        const response = await login();

        expect(response.status).toBe(200);
        const text = await response.text();
        expect(text).not.toContain(ACCESS_TOKEN);
        expect(text).not.toContain("refresh-new");
        const setCookie = response.headers.get("Set-Cookie") ?? "";
        expect(setCookie).toContain("; Path=/; HttpOnly; SameSite=Lax; ");
        expect(Number(/Max-Age=(\d+)$/.exec(setCookie)?.[1])).toBeGreaterThan(1790);

        const id = cookieIdOf(response);
        expect(id).toBeDefined();
        const stored = id === undefined ? undefined : await runtime.store.get(id);
        expect(stored).toMatchObject({
            userId: USER_ID,
            accessToken: ACCESS_TOKEN,
            refreshToken: "refresh-new",
            keycloakSessionId: "kc-new",
        });
        expect(seen[0]?.body).toEqual(CREDENTIALS);
        expect(seen[0]?.headers.get("X-Forwarded-For")).toBe("203.0.113.7");
    });

    it("replaces the session an existing cookie points at", async () => {
        await seedOldSession();
        identity("/auth/login", issuedByIdentity);

        const response = await login({ Cookie: `pallet_session=${OLD_SESSION}` });

        expect(response.status).toBe(200);
        const id = cookieIdOf(response);
        expect(id).toBeDefined();
        expect(id).not.toBe(OLD_SESSION);
        await expect(runtime.store.get(OLD_SESSION)).resolves.toBeUndefined();
    });

    it("passes identity's 401 through and leaves the existing session alone", async () => {
        await seedOldSession();
        identity("/auth/login", () => error(401, "INVALID_CREDENTIALS", { message: "Invalid email or password" }));

        const response = await login({ Cookie: `pallet_session=${OLD_SESSION}` });

        expect(response.status).toBe(401);
        expect(await response.json()).toMatchObject({
            error: "INVALID_CREDENTIALS",
            message: "Invalid email or password",
        });
        expect(response.headers.has("Set-Cookie")).toBe(false);
        await expect(runtime.store.get(OLD_SESSION)).resolves.toBeDefined();
    });

    it("passes a rate limit through with its retry time", async () => {
        identity("/auth/login", () =>
            error(429, "TOO_MANY_REQUESTS", { meta: { retryAfter: 30 }, headers: { "Retry-After": "30" } }),
        );

        const response = await login();

        expect(response.status).toBe(429);
        expect(response.headers.get("Retry-After")).toBe("30");
        expect(await response.json()).toMatchObject({ error: "TOO_MANY_REQUESTS", meta: { retryAfter: 30 } });
    });

    it.each([
        ["a cross-origin request", { Origin: "https://evil.example" }],
        ["a request without the CSRF header", { "X-Pallet-Request": "" }],
    ])("refuses %s with 403 before calling identity", async (_, headers) => {
        const seen = identity("/auth/login", issuedByIdentity);

        const response = await login(headers);

        expect(response.status).toBe(403);
        expect(await response.json()).toMatchObject({ error: "CSRF_REJECTED" });
        expect(seen).toEqual([]);
    });
});

describe("POST /api/session/logout", () => {
    it("revokes the refresh token at identity, deletes the session and clears the cookie", async () => {
        await seedOldSession();
        const seen = identity("/auth/logout", () => new HttpResponse(null, { status: 204 }));

        const response = await logout({ Cookie: `pallet_session=${OLD_SESSION}` });

        expect(response.status).toBe(204);
        expect(response.headers.get("Set-Cookie")).toBe("pallet_session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
        expect(seen[0]?.body).toEqual({ refreshToken: "refresh-old" });
        expect(seen[0]?.headers.get("Authorization")).toBe("Bearer access-old");
        await expect(runtime.store.get(OLD_SESSION)).resolves.toBeUndefined();
    });

    it("deletes the session even when identity answers 503", async () => {
        await seedOldSession();
        identity("/auth/logout", () => error(503, "SERVICE_UNAVAILABLE"));

        const response = await logout({ Cookie: `pallet_session=${OLD_SESSION}` });

        expect(response.status).toBe(204);
        expect(response.headers.get("Set-Cookie")).toContain("Max-Age=0");
        await expect(runtime.store.get(OLD_SESSION)).resolves.toBeUndefined();
    });

    it("answers 204 without a session, and again for the same cookie", async () => {
        const seen = identity("/auth/logout", () => new HttpResponse(null, { status: 204 }));

        expect((await logout()).status).toBe(204);
        expect((await logout({ Cookie: `pallet_session=${OLD_SESSION}` })).status).toBe(204);
        expect(seen).toEqual([]);
    });

    it("refuses a cross-origin logout and keeps the session", async () => {
        await seedOldSession();

        const response = await logout({ Cookie: `pallet_session=${OLD_SESSION}`, Origin: "https://evil.example" });

        expect(response.status).toBe(403);
        await expect(runtime.store.get(OLD_SESSION)).resolves.toBeDefined();
    });
});
