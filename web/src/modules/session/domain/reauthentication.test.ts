import { ApiError, SessionExpiredError } from "@/shared/domain/errors";
import { describe, expect, it } from "vitest";
import { reauthenticationSchema, requiresReauthentication } from "./reauthentication";

const apiError = (status: number, code: string) => new ApiError(status, code, "message", [], {}, undefined);

describe("requiresReauthentication", () => {
    it.each([
        [apiError(403, "REAUTHENTICATION_REQUIRED"), true],
        [apiError(403, "INSUFFICIENT_ROLE"), false],
        [apiError(401, "REAUTHENTICATION_REQUIRED"), false],
        [new SessionExpiredError(), false],
        [new Error("REAUTHENTICATION_REQUIRED"), false],
    ])("%o is %s", (error, required) => {
        expect(requiresReauthentication(error)).toBe(required);
    });
});

describe("reauthenticationSchema", () => {
    it("asks for a password and keeps it exactly as typed", () => {
        expect(reauthenticationSchema.safeParse({ password: "" }).error?.issues[0]?.message).toBe(
            "Enter your password",
        );
        expect(reauthenticationSchema.parse({ password: " pw " })).toEqual({ password: " pw " });
    });
});
