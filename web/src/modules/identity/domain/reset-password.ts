import { passwordSchema } from "@/shared/domain/password-policy";
import { z } from "zod";

/** The backend issues 32 random bytes as unpadded base64url; the bound only keeps junk out of a request. */
const RESET_TOKEN_PATTERN = /^[A-Za-z0-9_-]+$/;
export const MAX_RESET_TOKEN_LENGTH = 512;

export const resetTokenSchema = z.string().min(1).max(MAX_RESET_TOKEN_LENGTH).regex(RESET_TOKEN_PATTERN);

/** The token arrives in the URL, so it is untrusted: anything that can't be a reset token reads as none. */
export function parseResetToken(value: unknown): string | undefined {
    const result = resetTokenSchema.safeParse(value);
    return result.success ? result.data : undefined;
}

export const PASSWORD_MISMATCH_MESSAGE = "These don't match yet.";

export const confirmationSchema = z.string().min(1, "Type your new password again");

interface ConfirmedPasswordShape extends z.ZodRawShape {
    newPassword: typeof passwordSchema;
    confirmation: typeof confirmationSchema;
}

/** Puts a mismatch on the confirmation, and only once the confirmation has nothing else wrong with it. */
export function withMatchingConfirmation<S extends z.ZodObject<ConfirmedPasswordShape>>(schema: S) {
    return schema.refine(({ newPassword, confirmation }) => newPassword === confirmation, {
        path: ["confirmation"],
        message: PASSWORD_MISMATCH_MESSAGE,
        when: ({ issues }) => !issues.some((issue) => issue.path?.[0] === "confirmation"),
    });
}

export const newPasswordSchema = withMatchingConfirmation(
    z.object({ newPassword: passwordSchema, confirmation: confirmationSchema }),
);

export type NewPassword = z.output<typeof newPasswordSchema>;

export const passwordResetSchema = z.object({
    token: resetTokenSchema,
    newPassword: passwordSchema,
});

export type PasswordReset = z.output<typeof passwordResetSchema>;
