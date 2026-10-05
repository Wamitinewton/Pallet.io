import { parseEmail } from "@/shared/domain/email";

export const SIGN_IN_PATH = "/login";

export type SignInReason = "expired";

export function parseSignInReason(value: unknown): SignInReason | undefined {
    return value === "expired" ? value : undefined;
}

function parseFlag(value: unknown): boolean {
    return value === "1";
}

export const parseVerified = parseFlag;
export const parsePasswordReset = parseFlag;

export interface SignInPathOptions {
    readonly next?: string | undefined;
    readonly reason?: SignInReason | undefined;
    readonly email?: string | undefined;
    readonly verified?: boolean | undefined;
    readonly reset?: boolean | undefined;
}

export function signInPath({ next, reason, email, verified, reset }: SignInPathOptions = {}): string {
    const query = new URLSearchParams();
    if (reason !== undefined) query.set("reason", reason);
    if (verified === true) query.set("verified", "1");
    if (reset === true) query.set("reset", "1");
    if (email !== undefined && email.trim() !== "") query.set("email", email.trim());
    if (next !== undefined && next !== "") query.set("next", next);
    const search = query.toString();
    return search === "" ? SIGN_IN_PATH : `${SIGN_IN_PATH}?${search}`;
}

export interface SignInParams {
    readonly next: string | undefined;
    readonly reason: SignInReason | undefined;
    readonly verified: boolean;
    readonly reset: boolean;
    readonly email: string | undefined;
}

export function parseSignInParams(params: Readonly<Record<string, string | string[] | undefined>>): SignInParams {
    return {
        next: typeof params.next === "string" ? params.next : undefined,
        reason: parseSignInReason(params.reason),
        verified: parseVerified(params.verified),
        reset: parsePasswordReset(params.reset),
        email: parseEmail(params.email),
    };
}
