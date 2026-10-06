import { ApiError } from "@/shared/domain/errors";
import { passwordSchema } from "@/shared/domain/password-policy";
import { classifyRequestFailure, type RequestFailure } from "@/shared/domain/request-failure";
import { z } from "zod";
import { confirmationSchema, withMatchingConfirmation } from "./reset-password";

export const INVALID_CREDENTIALS = "INVALID_CREDENTIALS";

export const currentPasswordSchema = z.string().min(1, "Enter your current password");

export const changePasswordFormSchema = withMatchingConfirmation(
    z.object({
        currentPassword: currentPasswordSchema,
        newPassword: passwordSchema,
        confirmation: confirmationSchema,
    }),
);

export type ChangePasswordForm = z.output<typeof changePasswordFormSchema>;

export const passwordChangeSchema = z.object({
    currentPassword: currentPasswordSchema,
    newPassword: passwordSchema,
});

export type PasswordChange = z.output<typeof passwordChangeSchema>;

export function toPasswordChange({ currentPassword, newPassword }: ChangePasswordForm): PasswordChange {
    return { currentPassword, newPassword };
}

/** A wrong current password is a `401` about the request, never about the session. */
export type PasswordChangeFailure =
    { readonly kind: "wrong-current-password"; readonly error: ApiError } | RequestFailure;

export function classifyPasswordChangeFailure(error: unknown, now: Date): PasswordChangeFailure {
    if (error instanceof ApiError && error.is(INVALID_CREDENTIALS)) return { kind: "wrong-current-password", error };
    return classifyRequestFailure(error, now);
}
