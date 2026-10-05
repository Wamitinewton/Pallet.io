import { fixedClock } from "@/shared/domain/clock";
import { asUserId } from "@/shared/domain/ids";
import { beforeEach, describe, expect, it } from "vitest";
import { aSession, issuedTokens, SESSION_ID } from "../domain/testing/fixtures";
import { makeReplaceTokens, type ReplaceTokens } from "./replace-tokens";
import { InMemorySessionStore } from "./testing/in-memory";

let store: InMemorySessionStore;
let replaceTokens: ReplaceTokens;

beforeEach(async () => {
    store = new InMemorySessionStore();
    replaceTokens = makeReplaceTokens({ store, clock: fixedClock("2026-01-15T12:00:00Z") });
    await store.create(aSession());
});

describe("replaceTokens", () => {
    it("replaces the tokens on the same session id and hands back the previous pair", async () => {
        const result = await replaceTokens(aSession(), issuedTokens());

        expect(result).toMatchObject({
            status: "replaced",
            session: { id: SESSION_ID, accessToken: "access-2", refreshToken: "refresh-2" },
            previous: { refreshToken: "refresh-1" },
        });
        expect((await store.get(SESSION_ID))?.accessToken).toBe("access-2");
    });

    it("refuses tokens that belong to another user and changes nothing", async () => {
        const stranger = issuedTokens({ claims: { ...issuedTokens().claims, subject: asUserId("someone-else") } });

        await expect(replaceTokens(aSession(), stranger)).resolves.toEqual({ status: "different-user" });
        expect((await store.get(SESSION_ID))?.accessToken).toBe("access-1");
    });

    it("refuses to overwrite a newer pair with one derived from a stale session", async () => {
        await store.create(aSession({ refreshToken: "refresh-newer" }));

        await expect(replaceTokens(aSession(), issuedTokens())).resolves.toEqual({ status: "stale" });
        expect((await store.get(SESSION_ID))?.refreshToken).toBe("refresh-newer");
    });
});
