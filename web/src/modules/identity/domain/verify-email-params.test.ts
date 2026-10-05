import { describe, expect, it } from "vitest";
import { parseVerifyEmailParams } from "./verify-email-params";

describe("parseVerifyEmailParams", () => {
    it.each([
        [{ email: "amani@kilimalabs.co" }, "amani@kilimalabs.co"],
        [{ email: "<img src=x onerror=alert(1)>" }, undefined],
        [{ email: ["a@example.com", "b@example.com"] }, undefined],
        [{}, undefined],
    ])("reads %j as %s", (params, email) => {
        expect(parseVerifyEmailParams(params)).toEqual({ email });
    });
});
