import { describe, expect, it } from "vitest";
import {
    MAX_RESET_TOKEN_LENGTH,
    newPasswordSchema,
    parseResetToken,
    PASSWORD_MISMATCH_MESSAGE,
    passwordResetSchema,
} from "./reset-password";

const TOKEN = "q3Jx0f2Zr9VbL1mN8sT4uW6yA7cE5gH0iK2oP_-dQ1s";

function issuesOf(value: unknown) {
    return (newPasswordSchema.safeParse(value).error?.issues ?? []).map(({ path, message }) => ({
        field: path.join("."),
        message,
    }));
}

describe("newPasswordSchema", () => {
    it("accepts a password that meets the policy, typed twice", () => {
        expect(
            newPasswordSchema.safeParse({
                newPassword: "mango-season-in-kisumu",
                confirmation: "mango-season-in-kisumu",
            }).success,
        ).toBe(true);
    });

    it("puts a mismatch on the confirmation", () => {
        expect(issuesOf({ newPassword: "mango-season-in-kisumu", confirmation: "mango-season-in-kisum" })).toEqual([
            { field: "confirmation", message: PASSWORD_MISMATCH_MESSAGE },
        ]);
    });

    it("reports a mismatch alongside a password the policy refuses", () => {
        expect(issuesOf({ newPassword: "short", confirmation: "shorter" })).toEqual([
            { field: "newPassword", message: "Use at least 12 characters" },
            { field: "confirmation", message: PASSWORD_MISMATCH_MESSAGE },
        ]);
    });

    it("asks for the confirmation before comparing", () => {
        expect(issuesOf({ newPassword: "mango-season-in-kisumu", confirmation: "" })).toEqual([
            { field: "confirmation", message: "Type your new password again" },
        ]);
    });

    it("refuses a matching pair the policy refuses", () => {
        expect(issuesOf({ newPassword: " ".repeat(14), confirmation: " ".repeat(14) })).toEqual([
            { field: "newPassword", message: "Use at least 12 characters" },
        ]);
    });
});

describe("parseResetToken", () => {
    it.each([
        [TOKEN, TOKEN],
        ["", undefined],
        ["abc def", undefined],
        ["<script>", undefined],
        ["a".repeat(MAX_RESET_TOKEN_LENGTH + 1), undefined],
        [[TOKEN, TOKEN], undefined],
        [undefined, undefined],
    ])("reads %j as %s", (value, token) => {
        expect(parseResetToken(value)).toBe(token);
    });
});

describe("passwordResetSchema", () => {
    it("needs a token and a password the policy accepts", () => {
        expect(passwordResetSchema.safeParse({ token: TOKEN, newPassword: "mango-season-in-kisumu" }).success).toBe(
            true,
        );
        expect(passwordResetSchema.safeParse({ token: "", newPassword: "mango-season-in-kisumu" }).success).toBe(false);
        expect(passwordResetSchema.safeParse({ token: TOKEN, newPassword: "short" }).success).toBe(false);
    });
});
