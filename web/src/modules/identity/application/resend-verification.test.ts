import { describe, expect, it } from "vitest";
import { makeResendVerification } from "./resend-verification";
import { ScriptedVerificationRepository } from "./testing/in-memory";

describe("resendVerification", () => {
    it("asks for a new code for the trimmed address", async () => {
        const verifications = new ScriptedVerificationRepository();

        await makeResendVerification(verifications)(" amani@kilimalabs.co ");

        expect(verifications.resent).toEqual(["amani@kilimalabs.co"]);
    });

    it("refuses something that isn't an email", async () => {
        const verifications = new ScriptedVerificationRepository();

        await expect(makeResendVerification(verifications)("amani")).rejects.toThrow();
        expect(verifications.resent).toEqual([]);
    });
});
