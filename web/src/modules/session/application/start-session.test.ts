import { fixedClock } from "@/shared/domain/clock";
import { ApiError } from "@/shared/domain/errors";
import { beforeEach, describe, expect, it } from "vitest";
import { aSession, sessionId } from "../domain/testing/fixtures";
import { makeStartSession, type StartSession } from "./start-session";
import { InMemorySessionStore, ScriptedTokenIssuer, sequentialSessionIds } from "./testing/in-memory";

const grant = { email: "ada@example.com", password: "correct horse" };

let store: InMemorySessionStore;
let issuer: ScriptedTokenIssuer;
let startSession: StartSession;

beforeEach(() => {
    store = new InMemorySessionStore();
    issuer = new ScriptedTokenIssuer();
    startSession = makeStartSession({
        store,
        issuer,
        ids: sequentialSessionIds("b"),
        clock: fixedClock("2026-01-15T12:00:00Z"),
    });
});

describe("startSession", () => {
    it("stores a new session under a fresh id", async () => {
        const session = await startSession({ grant, origin: {} });

        expect(session.id).toBe(sessionId("b"));
        expect(store.sessions.get(sessionId("b"))).toEqual(session);
    });

    it("deletes the session it replaces, so an old cookie can never come back", async () => {
        await store.create(aSession({ id: sessionId("a") }));

        await startSession({ grant, origin: {}, replacing: sessionId("a") });

        expect([...store.sessions.keys()]).toEqual([sessionId("b")]);
    });

    it("stores nothing and keeps the previous session when sign-in fails", async () => {
        await store.create(aSession({ id: sessionId("a") }));
        issuer.nextLogin = () => Promise.reject(new ApiError(401, "INVALID_CREDENTIALS", "Wrong", [], {}, undefined));

        await expect(startSession({ grant, origin: {}, replacing: sessionId("a") })).rejects.toMatchObject({
            code: "INVALID_CREDENTIALS",
        });
        expect([...store.sessions.keys()]).toEqual([sessionId("a")]);
    });
});
