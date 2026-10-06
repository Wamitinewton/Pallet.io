import { ApiError } from "@/shared/domain/errors";
import { describe, expect, it } from "vitest";
import { makeChangePassword } from "./change-password";
import { ScriptedAccountRepository } from "./testing/in-memory";

const CHANGE = { currentPassword: "old-river-crossing", newPassword: "mango-season-in-kisumu" };

describe("changePassword", () => {
    it("sends the current and the new password", async () => {
        const accounts = new ScriptedAccountRepository();

        await makeChangePassword(accounts)(CHANGE);

        expect(accounts.passwordChanges).toEqual([CHANGE]);
    });

    it("refuses a new password the policy refuses without asking the backend", async () => {
        const accounts = new ScriptedAccountRepository();

        await expect(makeChangePassword(accounts)({ ...CHANGE, newPassword: "short" })).rejects.toThrow();
        expect(accounts.passwordChanges).toEqual([]);
    });

    it("passes a wrong current password through", async () => {
        const accounts = new ScriptedAccountRepository();
        const refusal = new ApiError(401, "INVALID_CREDENTIALS", "Invalid credentials.", [], {}, undefined);
        accounts.nextChangePassword = () => Promise.reject(refusal);

        await expect(makeChangePassword(accounts)(CHANGE)).rejects.toBe(refusal);
    });
});
