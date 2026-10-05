import { describe, expect, it } from "vitest";
import { makeRequestPasswordReset } from "./request-password-reset";
import { ScriptedPasswordRepository } from "./testing/in-memory";

describe("requestPasswordReset", () => {
    it("asks for a link for the trimmed address", async () => {
        const passwords = new ScriptedPasswordRepository();

        await makeRequestPasswordReset(passwords)(" amani@kilimalabs.co ");

        expect(passwords.resetRequests).toEqual(["amani@kilimalabs.co"]);
    });

    it("refuses something that isn't an email", async () => {
        const passwords = new ScriptedPasswordRepository();

        await expect(makeRequestPasswordReset(passwords)("amani")).rejects.toThrow();
        expect(passwords.resetRequests).toEqual([]);
    });
});
