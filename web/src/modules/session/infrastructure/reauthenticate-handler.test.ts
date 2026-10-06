import { fixedClock } from "@/shared/domain/clock";
import { ApiError, NetworkError } from "@/shared/domain/errors";
import { asUserId } from "@/shared/domain/ids";
import { createLogger } from "@/shared/infrastructure/logging/logger";
import { beforeEach, describe, expect, it } from "vitest";
import { makeReauthenticate } from "../application/reauthenticate";
import { makeReplaceTokens } from "../application/replace-tokens";
import type { ResolvedSession } from "../application/resolve-session";
import { InMemorySessionStore, ScriptedTokenIssuer } from "../application/testing/in-memory";
import { aSession, issuedTokens, SESSION_ID } from "../domain/testing/fixtures";
import { createReauthenticateHandler, MAX_REAUTHENTICATE_BODY_BYTES } from "./reauthenticate-handler";
import { sessionCookie } from "./session-cookie";

const ORIGIN = "http://localhost:5173";
const clock = fixedClock("2026-01-15T12:00:00Z");

let store: InMemorySessionStore;
let issuer: ScriptedTokenIssuer;
let resolved: ResolvedSession;
let handle: (request: Request) => Promise<Response>;

const reauthenticate = (body: string, headers: Record<string, string> = {}) =>
    handle(
        new Request(`${ORIGIN}/api/session/reauthenticate`, {
            method: "POST",
            headers: {
                Origin: ORIGIN,
                "X-Pallet-Request": "1",
                "Content-Type": "application/json",
                Cookie: `pallet_session=${SESSION_ID}`,
                ...headers,
            },
            body,
        }),
    );

const password = JSON.stringify({ password: "correct horse" });
const json = async (response: Response) => (await response.json()) as Record<string, unknown>;

beforeEach(async () => {
    store = new InMemorySessionStore();
    issuer = new ScriptedTokenIssuer();
    await store.create(aSession());
    resolved = { status: "active", session: aSession(), refreshed: false };
    handle = createReauthenticateHandler({
        cookie: sessionCookie(ORIGIN),
        resolveSession: () => Promise.resolve(resolved),
        reauthenticate: makeReauthenticate({ store, issuer, replaceTokens: makeReplaceTokens({ store, clock }) }),
        publicOrigin: ORIGIN,
        trustedProxyHops: 0,
        clock,
        logger: createLogger("fatal"),
    });
});

describe("reauthenticate handler", () => {
    it("keeps the session id and re-issues its cookie for the new refresh token's lifetime", async () => {
        const response = await reauthenticate(password);

        expect(response.status).toBe(200);
        expect(response.headers.get("Set-Cookie")).toBe(
            `pallet_session=${SESSION_ID}; Path=/; HttpOnly; SameSite=Lax; Max-Age=1800`,
        );
        expect(await json(response)).toEqual({ success: true, message: "Reauthenticated", data: { ok: true } });
        expect(await store.get(SESSION_ID)).toMatchObject({ accessToken: "access-2", refreshToken: "refresh-2" });
        expect(issuer.revoked).toEqual(["refresh-1"]);
    });

    it.each([
        ["malformed JSON", "{"],
        ["an empty password", JSON.stringify({ password: "" })],
    ])("answers %s with VALIDATION_ERROR without calling identity", async (_, body) => {
        const response = await reauthenticate(body);

        expect(response.status).toBe(400);
        expect(await json(response)).toMatchObject({ error: "VALIDATION_ERROR" });
        expect(issuer.loggedIn).toEqual([]);
    });

    it("refuses an oversized body", async () => {
        const response = await reauthenticate(JSON.stringify({ password: "x".repeat(MAX_REAUTHENTICATE_BODY_BYTES) }));

        expect(response.status).toBe(413);
        expect(issuer.loggedIn).toEqual([]);
    });

    it("rejects a request without the CSRF header", async () => {
        const response = await reauthenticate(password, { "X-Pallet-Request": "" });

        expect(response.status).toBe(403);
        expect(issuer.loggedIn).toEqual([]);
    });

    it("answers SESSION_EXPIRED and clears the cookie for an ended session", async () => {
        resolved = { status: "expired" };

        const response = await reauthenticate(password);

        expect(response.status).toBe(401);
        expect(await json(response)).toMatchObject({ error: "SESSION_EXPIRED" });
        expect(response.headers.get("Set-Cookie")).toContain("Max-Age=0");
        expect(issuer.loggedIn).toEqual([]);
    });

    it("relays a wrong password and keeps the session", async () => {
        issuer.nextLogin = () =>
            Promise.reject(new ApiError(401, "INVALID_CREDENTIALS", "Invalid email or password", [], {}, "corr-1"));

        const response = await reauthenticate(password);

        expect(response.status).toBe(401);
        expect(await json(response)).toMatchObject({ error: "INVALID_CREDENTIALS" });
        expect(response.headers.has("Set-Cookie")).toBe(false);
        expect(await store.get(SESSION_ID)).toEqual(aSession());
    });

    it("refuses tokens for another account with 403 and replaces nothing", async () => {
        issuer.nextLogin = () =>
            Promise.resolve(
                issuedTokens({ claims: { subject: asUserId("someone-else"), email: undefined, sessionId: undefined } }),
            );

        const response = await reauthenticate(password);

        expect(response.status).toBe(403);
        expect(await json(response)).toMatchObject({ error: "IDENTITY_MISMATCH" });
        expect(await store.get(SESSION_ID)).toEqual(aSession());
    });

    it("answers 502 when identity can't be reached", async () => {
        issuer.nextLogin = () => Promise.reject(new NetworkError());

        const response = await reauthenticate(password);

        expect(response.status).toBe(502);
        expect(await json(response)).toMatchObject({ error: "BAD_GATEWAY" });
    });
});
