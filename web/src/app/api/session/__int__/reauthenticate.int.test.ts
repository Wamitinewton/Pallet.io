import { sessionRuntime, type SessionRuntime } from "@/composition/session";
import { openSession } from "@/modules/session/domain/session";
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
import * as reauthenticateRoute from "../reauthenticate/route";

const SESSION = sessionId("a");
const FRESH_ACCESS_TOKEN = fakeJwt({ sub: USER_ID, email: "ada@example.com", sid: "kc-fresh" });

let redis: TestRedis;
let runtime: SessionRuntime;

interface Seen {
    readonly headers: Headers;
    readonly body: unknown;
}

const reauthenticate = (headers: Record<string, string> = {}, body: unknown = { password: "correct horse" }) =>
    reauthenticateRoute.POST(
        new Request(`${TEST_ORIGIN}/api/session/reauthenticate`, {
            method: "POST",
            headers: {
                Origin: TEST_ORIGIN,
                "X-Pallet-Request": "1",
                "Content-Type": "application/json",
                Cookie: `pallet_session=${SESSION}`,
                ...headers,
            },
            body: JSON.stringify(body),
        }),
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

const issuedFor = (accessToken: string, refreshToken: string) => () =>
    ok({ accessToken, expiresIn: 300, refreshToken, refreshExpiresIn: 1800 });

const revokedByIdentity = () => identity("/auth/logout", () => new HttpResponse(null, { status: 204 }));

async function seedSession(): Promise<void> {
    await runtime.store.create(
        openSession(
            SESSION,
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
    await seedSession();
});

describe("POST /api/session/reauthenticate", () => {
    it("signs in again as the session's account and replaces its tokens under the same session id", async () => {
        const signedIn = identity("/auth/login", issuedFor(FRESH_ACCESS_TOKEN, "refresh-fresh"));
        revokedByIdentity();

        const response = await reauthenticate();

        expect(response.status).toBe(200);
        const text = await response.text();
        expect(text).not.toContain(FRESH_ACCESS_TOKEN);
        expect(text).not.toContain("refresh-fresh");
        expect(response.headers.get("Set-Cookie")).toMatch(new RegExp(`^pallet_session=${SESSION}; `));
        expect(signedIn[0]?.body).toEqual({ email: "ada@example.com", password: "correct horse" });
        expect(await runtime.store.get(SESSION)).toMatchObject({
            userId: USER_ID,
            accessToken: FRESH_ACCESS_TOKEN,
            refreshToken: "refresh-fresh",
            keycloakSessionId: "kc-fresh",
        });
    });

    it("revokes the previous Keycloak session with the old refresh token and the new bearer", async () => {
        identity("/auth/login", issuedFor(FRESH_ACCESS_TOKEN, "refresh-fresh"));
        const revoked = revokedByIdentity();

        await reauthenticate();

        expect(revoked).toHaveLength(1);
        expect(revoked[0]?.body).toEqual({ refreshToken: "refresh-old" });
        expect(revoked[0]?.headers.get("Authorization")).toBe(`Bearer ${FRESH_ACCESS_TOKEN}`);
    });

    it("refuses tokens for a different account and replaces nothing", async () => {
        const strangerToken = fakeJwt({ sub: "someone-else", email: "ada@example.com", sid: "kc-stranger" });
        identity("/auth/login", issuedFor(strangerToken, "refresh-stranger"));
        const revoked = revokedByIdentity();

        const response = await reauthenticate();

        expect(response.status).toBe(403);
        expect(await response.json()).toMatchObject({ error: "IDENTITY_MISMATCH" });
        expect(response.headers.has("Set-Cookie")).toBe(false);
        expect(await runtime.store.get(SESSION)).toMatchObject({
            accessToken: "access-old",
            refreshToken: "refresh-old",
        });
        expect(revoked.map((seen) => seen.body)).toEqual([{ refreshToken: "refresh-stranger" }]);
    });

    it("passes INVALID_CREDENTIALS through and keeps the session", async () => {
        identity("/auth/login", () => error(401, "INVALID_CREDENTIALS", { message: "Invalid email or password" }));
        const revoked = revokedByIdentity();

        const response = await reauthenticate({}, { password: "wrong" });

        expect(response.status).toBe(401);
        expect(await response.json()).toMatchObject({
            error: "INVALID_CREDENTIALS",
            message: "Invalid email or password",
        });
        expect(response.headers.has("Set-Cookie")).toBe(false);
        expect(await runtime.store.get(SESSION)).toMatchObject({ refreshToken: "refresh-old" });
        expect(revoked).toEqual([]);
    });

    it("passes a rate limit through with its retry time", async () => {
        identity("/auth/login", () =>
            error(429, "TOO_MANY_REQUESTS", { meta: { retryAfter: 30 }, headers: { "Retry-After": "30" } }),
        );

        const response = await reauthenticate();

        expect(response.status).toBe(429);
        expect(response.headers.get("Retry-After")).toBe("30");
    });

    it("answers SESSION_EXPIRED without a session, before calling identity", async () => {
        const signedIn = identity("/auth/login", issuedFor(FRESH_ACCESS_TOKEN, "refresh-fresh"));

        const response = await reauthenticate({ Cookie: "" });

        expect(response.status).toBe(401);
        expect(await response.json()).toMatchObject({ error: "SESSION_EXPIRED" });
        expect(signedIn).toEqual([]);
    });

    it("refuses a cross-origin request before calling identity", async () => {
        const signedIn = identity("/auth/login", issuedFor(FRESH_ACCESS_TOKEN, "refresh-fresh"));

        const response = await reauthenticate({ Origin: "https://evil.example" });

        expect(response.status).toBe(403);
        expect(await response.json()).toMatchObject({ error: "CSRF_REJECTED" });
        expect(signedIn).toEqual([]);
    });
});
