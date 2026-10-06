import { describe, expect, it } from "vitest";
import { makeGetMyProfile } from "./get-my-profile";
import { ScriptedAccountRepository } from "./testing/in-memory";

describe("getMyProfile", () => {
    it("reads the caller's own profile", async () => {
        const accounts = new ScriptedAccountRepository();

        expect(await makeGetMyProfile(accounts)()).toEqual(accounts.profile);
    });
});
