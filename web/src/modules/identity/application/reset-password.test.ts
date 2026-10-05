import { describe, expect, it } from "vitest";
import { makeResetPassword } from "./reset-password";
import { ScriptedPasswordRepository } from "./testing/in-memory";

const TOKEN = "q3Jx0f2Zr9VbL1mN8sT4uW6yA7cE5gH0iK2oP_-dQ1s";

describe("resetPassword", () => {
    it("sends the token with the new password", async () => {
        const passwords = new ScriptedPasswordRepository();

        await makeResetPassword(passwords)({ token: TOKEN, newPassword: "mango-season-in-kisumu" });

        expect(passwords.resets).toEqual([{ token: TOKEN, newPassword: "mango-season-in-kisumu" }]);
    });

    it("refuses a password the policy refuses without asking the backend", async () => {
        const passwords = new ScriptedPasswordRepository();

        await expect(makeResetPassword(passwords)({ token: TOKEN, newPassword: "short" })).rejects.toThrow();
        expect(passwords.resets).toEqual([]);
    });

    it("passes a refusal through", async () => {
        const passwords = new ScriptedPasswordRepository();
        const refusal = new Error("INVALID_TOKEN");
        passwords.nextReset = () => Promise.reject(refusal);

        await expect(
            makeResetPassword(passwords)({ token: TOKEN, newPassword: "mango-season-in-kisumu" }),
        ).rejects.toBe(refusal);
    });
});
