import { describe, expect, it } from "vitest";
import { ScriptedAccountRepository } from "./testing/in-memory";
import { makeUpdateProfile } from "./update-profile";

describe("updateProfile", () => {
    it("sends the trimmed display name", async () => {
        const accounts = new ScriptedAccountRepository();

        await makeUpdateProfile(accounts)({ displayName: "  Amani O. " });

        expect(accounts.profileUpdates).toEqual([{ displayName: "Amani O." }]);
    });

    it("refuses a blank name without asking the backend", async () => {
        const accounts = new ScriptedAccountRepository();

        await expect(makeUpdateProfile(accounts)({ displayName: "  " })).rejects.toThrow();
        expect(accounts.profileUpdates).toEqual([]);
    });
});
