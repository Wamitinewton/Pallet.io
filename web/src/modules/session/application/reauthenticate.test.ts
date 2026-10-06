import { fixedClock } from "@/shared/domain/clock";
import { ApiError } from "@/shared/domain/errors";
import { asUserId } from "@/shared/domain/ids";
import { beforeEach, describe, expect, it } from "vitest";
import { aSession, issuedTokens, SESSION_ID } from "../domain/testing/fixtures";
import { makeReauthenticate, type Reauthenticate } from "./reauthenticate";
import { makeReplaceTokens } from "./replace-tokens";
import { InMemorySessionStore, ScriptedTokenIssuer } from "./testing/in-memory";

const clock = fixedClock("2026-01-15T12:00:00Z");
const fresh = issuedTokens({
    accessToken: "access-fresh",
    refreshToken: "refresh-fresh",
    claims: { subject: aSession().userId, email: "ada@example.com", sessionId: "kc-fresh" },
});

let store: InMemorySessionStore;
let issuer: ScriptedTokenIssuer;
let reauthenticate: Reauthenticate;

beforeEach(async () => {
    store = new InMemorySessionStore();
    issuer = new ScriptedTokenIssuer();
    issuer.nextLogin = () => Promise.resolve(fresh);
    reauthenticate = makeReauthenticate({ store, issuer, replaceTokens: makeReplaceTokens({ store, clock }) });
    await store.create(aSession());
});

describe("reauthenticate", () => {
    it("signs in again as the session's account and replaces its tokens under the same id", async () => {
        const result = await reauthenticate({ session: aSession(), password: "pw", origin: {} });

        expect(issuer.loggedIn).toEqual(["ada@example.com"]);
        expect(result).toMatchObject({ status: "reauthenticated", previousRevoked: true });
        expect(await store.get(SESSION_ID)).toMatchObject({
            accessToken: "access-fresh",
            refreshToken: "refresh-fresh",
            keycloakSessionId: "kc-fresh",
        });
    });

    it("revokes the previous Keycloak session with its refresh token", async () => {
        await reauthenticate({ session: aSession(), password: "pw", origin: {} });

        expect(issuer.revoked).toEqual(["refresh-1"]);
    });

    it("still succeeds when the previous session can't be revoked", async () => {
        issuer.nextRevoke = () => Promise.reject(new Error("identity is down"));

        const result = await reauthenticate({ session: aSession(), password: "pw", origin: {} });

        expect(result).toMatchObject({ status: "reauthenticated", previousRevoked: false });
    });

    it("refuses tokens for another account, replaces nothing and revokes them", async () => {
        issuer.nextLogin = () =>
            Promise.resolve(
                issuedTokens({
                    refreshToken: "refresh-stranger",
                    claims: { subject: asUserId("someone-else"), email: "ada@example.com", sessionId: "kc-x" },
                }),
            );

        const result = await reauthenticate({ session: aSession(), password: "pw", origin: {} });

        expect(result).toEqual({ status: "different-user" });
        expect(await store.get(SESSION_ID)).toEqual(aSession());
        expect(issuer.revoked).toEqual(["refresh-stranger"]);
    });

    it("lets a wrong password through and keeps the session", async () => {
        issuer.nextLogin = () =>
            Promise.reject(new ApiError(401, "INVALID_CREDENTIALS", "Invalid email or password", [], {}, undefined));

        await expect(reauthenticate({ session: aSession(), password: "nope", origin: {} })).rejects.toMatchObject({
            code: "INVALID_CREDENTIALS",
        });
        expect(await store.get(SESSION_ID)).toEqual(aSession());
        expect(issuer.revoked).toEqual([]);
    });

    it("builds on a refresh that landed meanwhile, revoking the refreshed token", async () => {
        await store.create(aSession({ refreshToken: "refresh-rotated" }));

        const result = await reauthenticate({ session: aSession(), password: "pw", origin: {} });

        expect(result.status).toBe("reauthenticated");
        expect(await store.get(SESSION_ID)).toMatchObject({ refreshToken: "refresh-fresh" });
        expect(issuer.revoked).toEqual(["refresh-rotated"]);
    });

    it("gives up, revoking the fresh tokens, when the session ended meanwhile", async () => {
        await store.delete(SESSION_ID);

        const result = await reauthenticate({ session: aSession(), password: "pw", origin: {} });

        expect(result).toEqual({ status: "ended" });
        expect(issuer.revoked).toEqual(["refresh-fresh"]);
    });
});
