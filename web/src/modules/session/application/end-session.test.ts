import { fixedClock } from "@/shared/domain/clock";
import { UnexpectedResponseError } from "@/shared/domain/errors";
import { beforeEach, describe, expect, it } from "vitest";
import { aSession, SESSION_ID } from "../domain/testing/fixtures";
import { makeEndSession, type EndSession } from "./end-session";
import { makeReplaceTokens } from "./replace-tokens";
import { makeResolveSession } from "./resolve-session";
import { InMemoryRefreshLock, InMemorySessionStore, ScriptedTokenIssuer } from "./testing/in-memory";

let store: InMemorySessionStore;
let issuer: ScriptedTokenIssuer;
let endSession: EndSession;

beforeEach(async () => {
    const clock = fixedClock("2026-01-15T12:00:00Z");
    store = new InMemorySessionStore();
    issuer = new ScriptedTokenIssuer();
    await store.create(aSession());
    const resolveSession = makeResolveSession({
        store,
        issuer,
        lock: new InMemoryRefreshLock(),
        replaceTokens: makeReplaceTokens({ store, clock }),
        clock,
        sleep: () => Promise.resolve(),
    });
    endSession = makeEndSession({ store, issuer, resolveSession });
});

describe("endSession", () => {
    it("revokes the refresh token and deletes the session", async () => {
        await expect(endSession(SESSION_ID)).resolves.toEqual({ ended: true, revoked: true });
        expect(issuer.revoked).toEqual(["refresh-1"]);
        expect(store.sessions.size).toBe(0);
    });

    it("deletes the session even when identity fails to revoke it", async () => {
        issuer.nextRevoke = () => Promise.reject(new UnexpectedResponseError(503, "down", undefined));

        await expect(endSession(SESSION_ID)).resolves.toEqual({ ended: true, revoked: false });
        expect(store.sessions.size).toBe(0);
    });

    it("is a no-op without a session", async () => {
        await expect(endSession(undefined)).resolves.toEqual({ ended: false, revoked: false });
        await store.delete(SESSION_ID);
        await expect(endSession(SESSION_ID)).resolves.toEqual({ ended: false, revoked: false });
        expect(issuer.revoked).toEqual([]);
    });
});
