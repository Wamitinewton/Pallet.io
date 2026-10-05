import { emailSchema } from "@/shared/domain/email";
import type { PasswordRepository } from "./ports";

export type RequestPasswordReset = (email: string) => Promise<void>;

export function makeRequestPasswordReset(passwords: PasswordRepository): RequestPasswordReset {
    return async (email) => {
        await passwords.requestReset(emailSchema.parse(email));
    };
}
