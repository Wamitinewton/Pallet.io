import { passwordResetSchema, type PasswordReset } from "../domain/reset-password";
import type { PasswordRepository } from "./ports";

export type ResetPassword = (reset: PasswordReset) => Promise<void>;

export function makeResetPassword(passwords: PasswordRepository): ResetPassword {
    return async (reset) => {
        await passwords.reset(passwordResetSchema.parse(reset));
    };
}
