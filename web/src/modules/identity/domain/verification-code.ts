import { emailSchema } from "@/shared/domain/email";
import { z } from "zod";

export const VERIFICATION_CODE_LENGTH = 8;
export const RESEND_COOLDOWN_SECONDS = 60;

export function resendAvailableAt(now: Date): Date {
    return new Date(now.getTime() + RESEND_COOLDOWN_SECONDS * 1000);
}

export function normalizeVerificationCode(raw: string): string {
    return raw.replace(/[\s-]+/g, "").toUpperCase();
}

export const verificationCodeSchema = z
    .string()
    .transform(normalizeVerificationCode)
    .pipe(
        z
            .string()
            .length(VERIFICATION_CODE_LENGTH, `Enter all ${String(VERIFICATION_CODE_LENGTH)} characters`)
            .regex(/^[A-Z0-9]+$/, "Use only the letters and numbers from the email"),
    );

export const emailVerificationSchema = z.object({
    email: emailSchema,
    code: verificationCodeSchema,
});

export type EmailVerification = z.output<typeof emailVerificationSchema>;
