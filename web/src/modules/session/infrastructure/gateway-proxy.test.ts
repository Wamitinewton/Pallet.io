import { fixedClock } from "@/shared/domain/clock";
import { NetworkError } from "@/shared/domain/errors";
import { asIsoInstant } from "@/shared/domain/instant";
import { createLogger } from "@/shared/infrastructure/logging/logger";
import { describe, expect, it } from "vitest";
import type { ResolvedSession } from "../application/resolve-session";
import { aSession, SESSION_ID } from "../domain/testing/fixtures";
import { createGatewayProxy, gatewayPathOf, type GatewayProxyOptions } from "./gateway-proxy";
import { sessionCookie } from "./session-cookie";

const ORIGIN = "http://localhost:5173";

function proxyWith(overrides: Partial<GatewayProxyOptions> & { resolved?: ResolvedSession | Error } = {}) {
    const { resolved = { status: "none" }, ...options } = overrides;
    return createGatewayProxy({
        gatewayUrl: "http://gateway.test",
        publicOrigin: ORIGIN,
        trustedProxyHops: 0,
        cookie: sessionCookie(ORIGIN),
        resolveSession: () => (resolved instanceof Error ? Promise.reject(resolved) : Promise.resolve(resolved)),
        store: { delete: () => Promise.resolve() },
        clock: fixedClock("2026-01-15T12:00:00Z"),
        logger: createLogger("fatal"),
        fetch: () => Promise.resolve(Response.json({ success: true, message: "OK", data: null })),
        ...options,
    });
}

const get = (cookie?: string) =>
    new Request(`${ORIGIN}/bff/org-team/orgs`, cookie === undefined ? {} : { headers: { Cookie: cookie } });

const codeOf = async (response: Response) => ((await response.json()) as { error: string }).error;

describe("gatewayPathOf", () => {
    it.each([
        [["identity", "auth", "login"], "identity/auth/login"],
        [["org-team", "orgs", "a b"], "org-team/orgs/a%20b"],
        [["git-integration", "installations", "a/b"], "git-integration/installations/a%2Fb"],
        [["notification", "notifications", "unread-count"], "notification/notifications/unread-count"],
    ])("forwards %j as %s", (segments, expected) => {
        expect(gatewayPathOf(segments)).toBe(expected);
    });

    it.each([
        [[]],
        [["billing", "invoices"]],
        [["actuator", "health"]],
        [["identity", "v3", "api-docs"]],
        [["org-team", "v3", "api-docs", "swagger-config"]],
        [["git-integration", "webhooks", "github"]],
        [["identity", "..", "admin"]],
        [["identity", ".", "auth"]],
        [["identity", "", "auth"]],
    ])("refuses %j", (segments) => {
        expect(gatewayPathOf(segments)).toBeUndefined();
    });
});

describe("createGatewayProxy", () => {
    it("answers 504 GATEWAY_TIMEOUT when the gateway is too slow", async () => {
        const proxy = proxyWith({
            timeoutMs: 20,
            fetch: (_, init) =>
                new Promise((_resolve, reject) => {
                    init?.signal?.addEventListener("abort", () => {
                        reject(init.signal?.reason as Error);
                    });
                }),
        });

        const response = await proxy(get(), ["org-team", "orgs"]);

        expect(response.status).toBe(504);
        expect(await codeOf(response)).toBe("GATEWAY_TIMEOUT");
    });

    it("answers 502 BAD_GATEWAY when the gateway cannot be reached", async () => {
        const proxy = proxyWith({ fetch: () => Promise.reject(new TypeError("fetch failed")) });

        const response = await proxy(get(), ["org-team", "orgs"]);

        expect(response.status).toBe(502);
        expect(await codeOf(response)).toBe("BAD_GATEWAY");
    });

    it("answers 503 without clearing the cookie when the session cannot be refreshed right now", async () => {
        const proxy = proxyWith({ resolved: new NetworkError() });

        const response = await proxy(get(`pallet_session=${SESSION_ID}`), ["org-team", "orgs"]);

        expect(response.status).toBe(503);
        expect(response.headers.has("Set-Cookie")).toBe(false);
    });

    it("re-issues the cookie with the extended lifetime after a refresh", async () => {
        const session = aSession({ refreshExpiresAt: asIsoInstant("2026-01-15T12:30:00Z") });
        const proxy = proxyWith({ resolved: { status: "active", session, refreshed: true } });

        const response = await proxy(get(`pallet_session=${SESSION_ID}`), ["org-team", "orgs"]);

        expect(response.headers.get("Set-Cookie")).toBe(
            `pallet_session=${SESSION_ID}; Path=/; HttpOnly; SameSite=Lax; Max-Age=1800`,
        );
    });

    it("leaves the cookie alone when nothing was refreshed", async () => {
        const proxy = proxyWith({ resolved: { status: "active", session: aSession(), refreshed: false } });

        const response = await proxy(get(`pallet_session=${SESSION_ID}`), ["org-team", "orgs"]);

        expect(response.headers.has("Set-Cookie")).toBe(false);
    });

    it("never follows an upstream redirect", async () => {
        let redirect: RequestRedirect | undefined;
        const proxy = proxyWith({
            fetch: (_, init) => {
                redirect = init?.redirect;
                return Promise.resolve(new Response(null, { status: 204 }));
            },
        });

        await proxy(get(), ["org-team", "orgs"]);

        expect(redirect).toBe("manual");
    });
});
