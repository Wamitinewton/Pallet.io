import { describe, expect, it } from "vitest";
import { makeConfirmPassword } from "./confirm-password";
import { ScriptedSessionGateway } from "./testing/in-memory";

describe("confirmPassword", () => {
    it("hands the password to the session gateway", async () => {
        const gateway = new ScriptedSessionGateway();

        await makeConfirmPassword(gateway)({ password: "correct horse" });

        expect(gateway.reauthentications).toEqual([{ password: "correct horse" }]);
    });

    it("refuses an empty password without asking the gateway", async () => {
        const gateway = new ScriptedSessionGateway();

        await expect(makeConfirmPassword(gateway)({ password: "" })).rejects.toThrow();
        expect(gateway.reauthentications).toEqual([]);
    });
});
