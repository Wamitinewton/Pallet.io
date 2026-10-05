import { emailSchema } from "@/shared/domain/email";
import type { VerificationRepository } from "./ports";

export type ResendVerification = (email: string) => Promise<void>;

export function makeResendVerification(verifications: VerificationRepository): ResendVerification {
    return async (email) => {
        await verifications.resend(emailSchema.parse(email));
    };
}
