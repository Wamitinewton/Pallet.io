import { describe, expect, it } from "vitest";
import { normalizeVerificationCode, resendAvailableAt, verificationCodeSchema } from "./verification-code";

describe("normalizeVerificationCode", () => {
    it.each([
        ["k7qm3wzp", "K7QM3WZP"],
        ["K7QM 3WZP", "K7QM3WZP"],
        ["k7qm-3wzp", "K7QM3WZP"],
        ["  k7 qm-3w zp ", "K7QM3WZP"],
    ])("%j becomes %j", (raw, code) => {
        expect(normalizeVerificationCode(raw)).toBe(code);
    });
});

describe("verificationCodeSchema", () => {
    it("accepts eight characters once normalized", () => {
        expect(verificationCodeSchema.parse("k7qm 3wzp")).toBe("K7QM3WZP");
    });

    it.each(["K7QM3WZ", "K7QM3WZPX", ""])("asks for all eight characters in %j", (code) => {
        expect(verificationCodeSchema.safeParse(code).error?.issues[0]?.message).toBe("Enter all 8 characters");
    });

    it("refuses characters a code never has", () => {
        expect(verificationCodeSchema.safeParse("K7QM3WZ!").success).toBe(false);
    });
});

describe("resendAvailableAt", () => {
    it("allows another code a minute later", () => {
        expect(resendAvailableAt(new Date("2026-01-15T12:00:00Z"))).toEqual(new Date("2026-01-15T12:01:00Z"));
    });
});
