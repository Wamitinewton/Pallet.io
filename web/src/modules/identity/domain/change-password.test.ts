import { ApiError, NetworkError } from "@/shared/domain/errors";
import { describe, expect, it } from "vitest";
import {
    changePasswordFormSchema,
    classifyPasswordChangeFailure,
    passwordChangeSchema,
    toPasswordChange,
} from "./change-password";
import { PASSWORD_MISMATCH_MESSAGE } from "./reset-password";

const NOW = new Date("2026-01-15T12:00:00Z");
const CURRENT = "old-river-crossing";
const NEXT = "mango-season-in-kisumu";

function issuesOf(value: unknown) {
    return (changePasswordFormSchema.safeParse(value).error?.issues ?? []).map(({ path, message }) => ({
        field: path.join("."),
        message,
    }));
}

const apiError = (status: number, code: string, meta: Record<string, unknown> = {}) =>
    new ApiError(status, code, "No", [], meta, undefined);

describe("changePasswordFormSchema", () => {
    it("accepts the current password and a new one typed twice", () => {
        expect(issuesOf({ currentPassword: CURRENT, newPassword: NEXT, confirmation: NEXT })).toEqual([]);
    });

    it("needs the current password", () => {
        expect(issuesOf({ currentPassword: "", newPassword: NEXT, confirmation: NEXT })).toEqual([
            { field: "currentPassword", message: "Enter your current password" },
        ]);
    });

    it("applies the password policy to the new password only", () => {
        expect(issuesOf({ currentPassword: "short", newPassword: "short", confirmation: "short" })).toEqual([
            { field: "newPassword", message: "Use at least 12 characters" },
        ]);
    });

    it("puts a mismatch on the confirmation", () => {
        expect(issuesOf({ currentPassword: CURRENT, newPassword: NEXT, confirmation: `${NEXT}!` })).toEqual([
            { field: "confirmation", message: PASSWORD_MISMATCH_MESSAGE },
        ]);
    });
});

describe("passwordChangeSchema", () => {
    it("is what the backend receives, without the confirmation", () => {
        const change = toPasswordChange({ currentPassword: CURRENT, newPassword: NEXT, confirmation: NEXT });

        expect(change).toEqual({ currentPassword: CURRENT, newPassword: NEXT });
        expect(passwordChangeSchema.safeParse(change).success).toBe(true);
        expect(passwordChangeSchema.safeParse({ currentPassword: CURRENT, newPassword: "short" }).success).toBe(false);
    });
});

describe("classifyPasswordChangeFailure", () => {
    it("reads INVALID_CREDENTIALS as a wrong current password", () => {
        expect(classifyPasswordChangeFailure(apiError(401, "INVALID_CREDENTIALS"), NOW).kind).toBe(
            "wrong-current-password",
        );
    });

    it("falls back to the shared classification", () => {
        expect(classifyPasswordChangeFailure(apiError(429, "TOO_MANY_REQUESTS", { retryAfter: 5 }), NOW)).toMatchObject(
            { kind: "rate-limited", retryAfterSeconds: 5 },
        );
        expect(classifyPasswordChangeFailure(apiError(400, "VALIDATION_ERROR"), NOW).kind).toBe("rejected");
        expect(classifyPasswordChangeFailure(new NetworkError(), NOW).kind).toBe("unavailable");
    });
});
