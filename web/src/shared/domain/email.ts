import { z } from "zod";

export const MAX_EMAIL_LENGTH = 254;

export const emailSchema = z
    .string()
    .trim()
    .min(1, "Enter your email address")
    .max(MAX_EMAIL_LENGTH, "Enter a valid email address")
    .pipe(z.email("Enter a valid email address"));

/** An email read from a URL or other untrusted input: the address when it is one, otherwise nothing. */
export function parseEmail(value: unknown): string | undefined {
    const result = emailSchema.safeParse(value);
    return result.success ? result.data : undefined;
}
