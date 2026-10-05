import { fixedClock } from "@/shared/domain/clock";
import { describe, expect, it } from "vitest";
import type { ResolvedSession } from "../application/resolve-session";
import { aSession, SESSION_ID } from "../domain/testing/fixtures";
import { sessionCookie } from "./session-cookie";
import { createSessionSummaryHandler } from "./session-summary";

const ORIGIN = "http://localhost:5173";

const handlerResolving = (resolved: ResolvedSession) =>
    createSessionSummaryHandler({
        cookie: sessionCookie(ORIGIN),
        resolveSession: () => Promise.resolve(resolved),
        trustedProxyHops: 0,
        clock: fixedClock("2026-01-15T12:00:00Z"),
    });

const request = new Request(`${ORIGIN}/api/session`, { headers: { Cookie: `pallet_session=${SESSION_ID}` } });

describe("GET /api/session", () => {
    it("summarizes the current session without any token or the session id", async () => {
        const response = await handlerResolving({ status: "active", session: aSession(), refreshed: false })(request);
        const text = await response.text();

        expect(response.status).toBe(200);
        expect(JSON.parse(text)).toEqual({
            success: true,
            message: "Current session",
            data: {
                userId: aSession().userId,
                email: "ada@example.com",
                keycloakSessionId: "kc-1",
                accessExpiresAt: aSession().accessExpiresAt,
            },
        });
        for (const secret of ["access-1", "refresh-1", SESSION_ID]) expect(text).not.toContain(secret);
        expect(response.headers.get("Cache-Control")).toBe("no-store");
    });

    it("answers 401 SESSION_EXPIRED and clears a dead cookie", async () => {
        const response = await handlerResolving({ status: "expired" })(request);

        expect(response.status).toBe(401);
        expect(((await response.json()) as { error: string }).error).toBe("SESSION_EXPIRED");
        expect(response.headers.get("Set-Cookie")).toContain("Max-Age=0");
    });

    it("answers 401 without touching cookies when there is no session at all", async () => {
        const response = await handlerResolving({ status: "none" })(new Request(`${ORIGIN}/api/session`));

        expect(response.status).toBe(401);
        expect(response.headers.has("Set-Cookie")).toBe(false);
    });
});
