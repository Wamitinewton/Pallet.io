import { ApiError } from "@/shared/domain/errors";
import { describe, expect, it } from "vitest";
import { ScriptedVerificationRepository } from "./testing/in-memory";
import { makeVerifyEmail } from "./verify-email";

describe("verifyEmail", () => {
    it("sends the normalized email and code", async () => {
        const verifications = new ScriptedVerificationRepository();

        await makeVerifyEmail(verifications)({ email: " amani@kilimalabs.co ", code: "k7qm-3wzp" });

        expect(verifications.verified).toEqual([{ email: "amani@kilimalabs.co", code: "K7QM3WZP" }]);
    });

    it("refuses an incomplete code without calling the port", async () => {
        const verifications = new ScriptedVerificationRepository();

        await expect(makeVerifyEmail(verifications)({ email: "amani@kilimalabs.co", code: "K7Q" })).rejects.toThrow();
        expect(verifications.verified).toEqual([]);
    });

    it("surfaces the port's error untouched", async () => {
        const verifications = new ScriptedVerificationRepository();
        const rejection = new ApiError(400, "INVALID_TOKEN", "Invalid", [], {}, undefined);
        verifications.nextVerify = () => Promise.reject(rejection);

        await expect(makeVerifyEmail(verifications)({ email: "amani@kilimalabs.co", code: "K7QM3WZP" })).rejects.toBe(
            rejection,
        );
    });
});
