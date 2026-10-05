import { describe, expect, it } from "vitest";
import { parseForgotPasswordParams, parseResetPasswordParams } from "./password-recovery-params";

describe("parseForgotPasswordParams", () => {
    it.each([
        [{ email: "amani@kilimalabs.co" }, "amani@kilimalabs.co"],
        [{ email: "not an email" }, undefined],
        [{ email: ["a@example.com", "b@example.com"] }, undefined],
        [{}, undefined],
    ])("reads %j as %s", (params, email) => {
        expect(parseForgotPasswordParams(params)).toEqual({ email });
    });
});

describe("parseResetPasswordParams", () => {
    it.each([
        [{ token: "q3Jx0f2Zr9VbL1mN8sT4uW6yA7cE5gH0iK2oP_-dQ1s" }, "q3Jx0f2Zr9VbL1mN8sT4uW6yA7cE5gH0iK2oP_-dQ1s"],
        [{ token: "a b" }, undefined],
        [{ token: ["a", "b"] }, undefined],
        [{}, undefined],
    ])("reads %j as %s", (params, token) => {
        expect(parseResetPasswordParams(params)).toEqual({ token });
    });
});
