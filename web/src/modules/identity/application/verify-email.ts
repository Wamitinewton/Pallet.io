import { emailVerificationSchema, type EmailVerification } from "../domain/verification-code";
import type { VerificationRepository } from "./ports";

export type VerifyEmail = (verification: EmailVerification) => Promise<void>;

export function makeVerifyEmail(verifications: VerificationRepository): VerifyEmail {
    return async (verification) => {
        await verifications.verify(emailVerificationSchema.parse(verification));
    };
}
