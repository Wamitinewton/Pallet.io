import { sessionRuntime, type SessionRuntime } from "@/composition/session";
import { openSession } from "@/modules/session/domain/session";
import { issuedTokens, sessionId } from "@/modules/session/domain/testing/fixtures";
import { lazyRedisConnection } from "@/modules/session/infrastructure/redis-client";
import { createLogger } from "@/shared/infrastructure/logging/logger";
import { error, ok } from "@/test/msw/envelopes";
import { gatewayUrl, server, TEST_ORIGIN } from "@/test/msw/server";
import { connectTestRedis, type TestRedis } from "@/test/redis";
import { testServerEnvVars } from "@/test/session-env";
import { http, HttpResponse } from "msw";
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import * as route from "../[...path]/route";

type Handler = (request: Request, context: { params: Promise<{ path: string[] }> }) => Promise<Response>;

const SESSION = sessionId("a");
const COOKIE = `pallet_session=${SESSION}`;
const ACCESS_TOKEN = "access-live";

let redis: TestRedis;
let runtime: SessionRuntime;
interface Forwarded {
    readonly url: string;
    readonly headers: Headers;
    readonly body: string;
}

let seen: Forwarded[];

const call = (handler: Handler, path: string, init: RequestInit = {}) => {
    const url = new URL(`/bff/${path}`, TEST_ORIGIN);
    const segments = url.pathname.slice("/bff/".length).split("/").map(decodeURIComponent);
    return handler(new Request(url, init), { params: Promise.resolve({ path: segments }) });
};

const sameOriginWrite = (headers: Record<string, string> = {}): Record<string, string> => ({
    Origin: TEST_ORIGIN,
    "X-Pallet-Request": "1",
    "Content-Type": "application/json",
    Cookie: COOKIE,
    ...headers,
});

const errorCodeOf = async (response: Response) => ((await response.json()) as { error: string }).error;

function recordAll(reply: () => Response | Promise<Response> = () => ok({ id: "o-1" })) {
    server.use(
        http.all(`${gatewayUrl("")}/*`, async ({ request }) => {
            seen.push({ url: request.url, headers: request.headers, body: await request.text() });
            return reply();
        }),
    );
}

async function seedSession(): Promise<void> {
    await runtime.store.create(
        openSession(
            SESSION,
            issuedTokens({ accessToken: ACCESS_TOKEN, refreshToken: "refresh-live" }),
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
    seen = [];
});

describe("/bff proxy", () => {
    it("attaches the session's bearer token and never forwards the browser's cookie", async () => {
        await seedSession();
        recordAll();

        const response = await call(route.GET, "org-team/orgs?page=1&size=20", {
            headers: { Cookie: `${COOKIE}; theme=dark`, Accept: "application/json", "X-Debug": "1" },
        });

        expect(response.status).toBe(200);
        expect(await response.json()).toEqual({ success: true, message: "OK", data: { id: "o-1" } });
        const [forwarded] = seen;
        expect(forwarded?.url).toBe(gatewayUrl("/org-team/orgs?page=1&size=20"));
        expect(forwarded?.headers.get("Authorization")).toBe(`Bearer ${ACCESS_TOKEN}`);
        expect(forwarded?.headers.get("Accept")).toBe("application/json");
        expect(forwarded?.headers.has("Cookie")).toBe(false);
        expect(forwarded?.headers.has("X-Debug")).toBe(false);
        expect(forwarded?.headers.get("X-Correlation-Id")).toMatch(/.+/);
        expect(response.headers.get("Cache-Control")).toBe("no-store");
    });

    it("forwards a request without a session anonymously", async () => {
        recordAll(() => ok({ available: true }));

        const response = await call(route.GET, "identity/signup/slug-availability?slug=acme");

        expect(response.status).toBe(200);
        expect(seen[0]?.headers.has("Authorization")).toBe(false);
    });

    it("forwards only the allow-listed headers of a same-origin write, with its body", async () => {
        await seedSession();
        recordAll();

        const response = await call(route.POST, "org-team/orgs", {
            method: "POST",
            headers: sameOriginWrite({
                "Idempotency-Key": "idem-1",
                "X-Confirm-Slug": "acme",
                "X-Forwarded-For": "6.6.6.6, 203.0.113.7",
                traceparent: "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                "X-Correlation-Id": "corr-browser",
                "Sec-Fetch-Site": "same-origin",
                Referer: `${TEST_ORIGIN}/orgs`,
            }),
            body: JSON.stringify({ name: "Acme" }),
        });

        expect(response.status).toBe(200);
        const forwarded = seen[0];
        expect(forwarded?.body).toBe(JSON.stringify({ name: "Acme" }));
        const headers = Object.fromEntries(forwarded?.headers ?? []);
        expect(headers).toMatchObject({
            authorization: `Bearer ${ACCESS_TOKEN}`,
            "content-type": "application/json",
            "idempotency-key": "idem-1",
            "x-confirm-slug": "acme",
            "x-correlation-id": "corr-browser",
            "x-forwarded-for": "203.0.113.7",
            traceparent: "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
        });
        for (const browserOnly of ["cookie", "origin", "x-pallet-request", "sec-fetch-site", "referer"]) {
            expect(headers).not.toHaveProperty(browserOnly);
        }
    });

    it.each([
        ["a cross-site Origin", { Origin: "https://evil.example" }],
        ["no Origin and a cross-site fetch", { Origin: "", "Sec-Fetch-Site": "cross-site" }],
        ["no CSRF header", { "X-Pallet-Request": "" }],
    ])("rejects a write with %s", async (_, overrides) => {
        await seedSession();
        recordAll();
        const headers = Object.fromEntries(
            Object.entries(sameOriginWrite(overrides)).filter(([, value]) => value !== ""),
        );

        const response = await call(route.POST, "org-team/orgs", { method: "POST", headers, body: "{}" });

        expect(response.status).toBe(403);
        expect(await errorCodeOf(response)).toBe("CSRF_REJECTED");
        expect(seen).toEqual([]);
    });

    it.each([
        "identity/v3/api-docs",
        "org-team/v3/api-docs/swagger-config",
        "git-integration/webhooks/github",
        "billing/invoices",
        "identity/%2E%2E/admin",
    ])("refuses %s with 404", async (path) => {
        recordAll();

        const response = await call(route.GET, path);

        expect(response.status).toBe(404);
        expect(seen).toEqual([]);
    });

    it("answers SESSION_EXPIRED and clears the cookie when the cookie points at no session", async () => {
        recordAll();

        const response = await call(route.GET, "org-team/orgs", { headers: { Cookie: COOKIE } });

        expect(response.status).toBe(401);
        expect(await errorCodeOf(response)).toBe("SESSION_EXPIRED");
        expect(response.headers.get("Set-Cookie")).toContain("pallet_session=; ");
        expect(seen).toEqual([]);
    });

    it("deletes a session the gateway no longer accepts and answers SESSION_EXPIRED", async () => {
        await seedSession();
        recordAll(() => error(401, "AUTHENTICATION_REQUIRED"));

        const response = await call(route.GET, "org-team/orgs", { headers: { Cookie: COOKIE } });

        expect(response.status).toBe(401);
        expect(await errorCodeOf(response)).toBe("SESSION_EXPIRED");
        expect(response.headers.get("Set-Cookie")).toContain("Max-Age=0");
        await expect(runtime.store.get(SESSION)).resolves.toBeUndefined();
    });

    it("passes any other 401 through and keeps the session", async () => {
        await seedSession();
        recordAll(() => error(401, "INVALID_CREDENTIALS"));

        const response = await call(route.POST, "identity/users/me/password", {
            method: "POST",
            headers: sameOriginWrite(),
            body: "{}",
        });

        expect(response.status).toBe(401);
        expect(await errorCodeOf(response)).toBe("INVALID_CREDENTIALS");
        expect(response.headers.has("Set-Cookie")).toBe(false);
        await expect(runtime.store.get(SESSION)).resolves.toBeDefined();
    });

    it("passes Retry-After and X-Correlation-Id through and never an upstream Set-Cookie", async () => {
        recordAll(() =>
            HttpResponse.json(
                { success: false, message: "Slow down", error: "TOO_MANY_REQUESTS", statusCode: 429 },
                {
                    status: 429,
                    headers: {
                        "Retry-After": "30",
                        "X-Correlation-Id": "corr-gateway",
                        "Set-Cookie": "upstream=1; Path=/",
                        "X-Internal": "secret",
                    },
                },
            ),
        );

        const response = await call(route.GET, "identity/signup/slug-availability?slug=acme");

        expect(response.status).toBe(429);
        expect(response.headers.get("Retry-After")).toBe("30");
        expect(response.headers.get("X-Correlation-Id")).toBe("corr-gateway");
        expect(response.headers.has("Set-Cookie")).toBe(false);
        expect(response.headers.has("X-Internal")).toBe(false);
    });

    it("passes an empty 204 through", async () => {
        await seedSession();
        recordAll(() => new HttpResponse(null, { status: 204 }));

        const response = await call(route.DELETE, "org-team/orgs/o-1", {
            method: "DELETE",
            headers: sameOriginWrite(),
        });

        expect(response.status).toBe(204);
        expect(await response.text()).toBe("");
    });

    it("refuses a body over 1 MB by its declared length", async () => {
        recordAll();
        const body = "x".repeat(1024 * 1024 + 1);

        const response = await call(route.POST, "org-team/orgs", {
            method: "POST",
            headers: sameOriginWrite({ "Content-Length": String(body.length) }),
            body,
        });

        expect(response.status).toBe(413);
        expect(seen).toEqual([]);
    });

    it("refuses a streamed body that grows past 1 MB", async () => {
        await seedSession();
        server.use(
            http.all(`${gatewayUrl("")}/*`, async ({ request }) => {
                const read = await request.arrayBuffer().then(
                    () => true,
                    () => false,
                );
                return read ? ok(null) : HttpResponse.error();
            }),
        );
        const chunk = new Uint8Array(256 * 1024);
        let sent = 0;
        const body = new ReadableStream<Uint8Array>({
            pull(controller) {
                if (sent++ < 5) controller.enqueue(chunk);
                else controller.close();
            },
        });

        const response = await call(route.POST, "org-team/orgs", {
            method: "POST",
            headers: sameOriginWrite(),
            body,
            duplex: "half",
        } as RequestInit);

        expect(response.status).toBe(413);
    });
});
