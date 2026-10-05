import { parseEmail } from "@/shared/domain/email";

export interface VerifyEmailParams {
    readonly email: string | undefined;
}

/** The email arrives in the URL, so it is untrusted: anything that isn't an address falls back to asking for one. */
export function parseVerifyEmailParams(
    params: Readonly<Record<string, string | string[] | undefined>>,
): VerifyEmailParams {
    return { email: parseEmail(params.email) };
}
