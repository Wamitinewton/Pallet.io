import { fixedClock } from "@/shared/domain/clock";
import { NetworkError } from "@/shared/domain/errors";
import { asUserId } from "@/shared/domain/ids";
import { asIsoInstant } from "@/shared/domain/instant";
import { beforeEach, describe, expect, it } from "vitest";
import { aSession, issuedTokens, SESSION_ID } from "../domain/testing/fixtures";
import { TokenRejectedError } from "./ports";
import { makeReplaceTokens } from "./replace-tokens";
import { makeResolveSession, RefreshTimeoutError, type ResolveSession } from "./resolve-session";
import { InMemoryRefreshLock, InMemorySessionStore, ScriptedTokenIssuer } from "./testing/in-memory";

const FRESH = "2026-01-15T12:00:00.000Z";
const IN_WINDOW = "2026-01-15T12:04:45.000Z";
const ACCESS_EXPIRED = "2026-01-15T12:06:00.000Z";

let store: InMemorySessionStore;
let issuer: ScriptedTokenIssuer;
let lock: InMemoryRefreshLock;
let sleeps: number;

function resolverAt(now: string, onSleep: () => void = () => undefined): ResolveSession {
    const clock = fixedClock(now);
    return makeResolveSession({
        store,
        issuer,
        lock,
        replaceTokens: makeReplaceTokens({ store, clock }),
        clock,
        sleep: () => {
            sleeps++;
            onSleep();
            return Promise.resolve();
        },
        pollAttempts: 3,
    });
}

beforeEach(async () => {
    store = new InMemorySessionStore();
    issuer = new ScriptedTokenIssuer();
    lock = new InMemoryRefreshLock();
    sleeps = 0;
    await store.create(aSession());
});

describe("resolveSession", () => {
    it("answers none without a cookie", async () => {
        await expect(resolverAt(FRESH)(undefined)).resolves.toEqual({ status: "none" });
        await expect(resolverAt(FRESH)("")).resolves.toEqual({ status: "none" });
    });

    it("answers expired for a malformed cookie without touching the store", async () => {
        await expect(resolverAt(FRESH)("../../etc/passwd")).resolves.toEqual({ status: "expired" });
    });

    it("answers expired for an unknown session", async () => {
        await store.delete(SESSION_ID);
        await expect(resolverAt(FRESH)(SESSION_ID)).resolves.toEqual({ status: "expired" });
    });

    it("leaves a fresh session untouched", async () => {
        await expect(resolverAt(FRESH)(SESSION_ID)).resolves.toEqual({
            status: "active",
            session: aSession(),
            refreshed: false,
        });
        expect(issuer.refreshed).toEqual([]);
    });

    it("refreshes inside the window and stores the new pair under the same id", async () => {
        const resolved = await resolverAt(IN_WINDOW)(SESSION_ID);

        expect(resolved).toMatchObject({
            status: "active",
            refreshed: true,
            session: { id: SESSION_ID, accessToken: "access-2" },
        });
        expect(issuer.refreshed).toEqual(["refresh-1"]);
        expect((await store.get(SESSION_ID))?.refreshToken).toBe("refresh-2");
        expect(lock.held.size).toBe(0);
    });

    it("deletes the session when identity rejects the refresh token", async () => {
        issuer.nextRefresh = () => Promise.reject(new TokenRejectedError("revoked"));

        await expect(resolverAt(IN_WINDOW)(SESSION_ID)).resolves.toEqual({ status: "expired" });
        expect(store.sessions.has(SESSION_ID)).toBe(false);
    });

    it("keeps a still-valid access token through a network failure", async () => {
        issuer.nextRefresh = () => Promise.reject(new NetworkError());

        await expect(resolverAt(IN_WINDOW)(SESSION_ID)).resolves.toEqual({
            status: "active",
            session: aSession(),
            refreshed: false,
        });
        expect(store.sessions.has(SESSION_ID)).toBe(true);
    });

    it("throws on a network failure once the access token has expired, without signing anyone out", async () => {
        issuer.nextRefresh = () => Promise.reject(new NetworkError());

        await expect(resolverAt(ACCESS_EXPIRED)(SESSION_ID)).rejects.toBeInstanceOf(NetworkError);
        expect(store.sessions.has(SESSION_ID)).toBe(true);
    });

    it("deletes a session whose refresh token has expired", async () => {
        await store.create(aSession({ refreshExpiresAt: asIsoInstant("2026-01-15T12:00:00.000Z") }));

        await expect(resolverAt(FRESH)(SESSION_ID)).resolves.toEqual({ status: "expired" });
        expect(store.sessions.has(SESSION_ID)).toBe(false);
    });

    it("refuses tokens issued to a different user", async () => {
        issuer.nextRefresh = () =>
            Promise.resolve(issuedTokens({ claims: { ...issuedTokens().claims, subject: asUserId("intruder") } }));

        await expect(resolverAt(IN_WINDOW)(SESSION_ID)).resolves.toEqual({ status: "expired" });
        expect(store.sessions.has(SESSION_ID)).toBe(false);
    });

    it("does not refresh again when another resolver already did, once it holds the lock", async () => {
        await store.create(
            aSession({ accessToken: "access-9", accessExpiresAt: asIsoInstant("2026-01-15T12:10:00.000Z") }),
        );
        const stale = aSession();
        const original = store.get.bind(store);
        let reads = 0;
        store.get = (id) => (reads++ === 0 ? Promise.resolve(stale) : original(id));

        await expect(resolverAt(IN_WINDOW)(SESSION_ID)).resolves.toMatchObject({
            session: { accessToken: "access-9" },
        });
        expect(issuer.refreshed).toEqual([]);
    });

    describe("while another request holds the refresh lock", () => {
        beforeEach(() => {
            lock.held.add(SESSION_ID);
        });

        it("waits for the new access token instead of spending the refresh token again", async () => {
            const resolve = resolverAt(IN_WINDOW, () => {
                if (sleeps === 2) void store.create(aSession({ accessToken: "access-2" }));
            });

            await expect(resolve(SESSION_ID)).resolves.toMatchObject({
                session: { accessToken: "access-2" },
                refreshed: false,
            });
            expect(issuer.refreshed).toEqual([]);
        });

        it("answers expired when the winner found the session revoked", async () => {
            const resolve = resolverAt(IN_WINDOW, () => {
                void store.delete(SESSION_ID);
            });

            await expect(resolve(SESSION_ID)).resolves.toEqual({ status: "expired" });
        });

        it("falls back to the current token when the winner never finishes", async () => {
            await expect(resolverAt(IN_WINDOW)(SESSION_ID)).resolves.toMatchObject({
                session: { accessToken: "access-1" },
            });
            expect(sleeps).toBe(3);
        });

        it("gives up when the winner never finishes and the token has expired", async () => {
            await expect(resolverAt(ACCESS_EXPIRED)(SESSION_ID)).rejects.toBeInstanceOf(RefreshTimeoutError);
        });
    });
});
