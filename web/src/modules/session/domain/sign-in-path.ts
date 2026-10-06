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

/** The backend's longest organization name; anything longer, or with control characters, isn't one. */
export const MAX_ORGANIZATION_NAME_LENGTH = 255;
const CONTROL_CHARACTER = /\p{Cc}/u;

/** The organization someone just joined through an invite, read from the URL only to greet them. */
export function parseJoinedOrganization(value: unknown): string | undefined {
    if (typeof value !== "string") return undefined;
    const name = value.trim();
    if (name === "" || name.length > MAX_ORGANIZATION_NAME_LENGTH || CONTROL_CHARACTER.test(name)) return undefined;
    return name;
}

export interface SignInPathOptions {
    readonly next?: string | undefined;
    readonly reason?: SignInReason | undefined;
    readonly email?: string | undefined;
    readonly verified?: boolean | undefined;
    readonly reset?: boolean | undefined;
    /** The organization an invite was just accepted for. */
    readonly joined?: string | undefined;
}

export function signInPath({ next, reason, email, verified, reset, joined }: SignInPathOptions = {}): string {
    const query = new URLSearchParams();
    if (reason !== undefined) query.set("reason", reason);
    if (verified === true) query.set("verified", "1");
    if (reset === true) query.set("reset", "1");
    if (joined !== undefined && joined.trim() !== "") query.set("joined", joined.trim());
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
    readonly joined: string | undefined;
    readonly email: string | undefined;
}

export function parseSignInParams(params: Readonly<Record<string, string | string[] | undefined>>): SignInParams {
    return {
        next: typeof params.next === "string" ? params.next : undefined,
        reason: parseSignInReason(params.reason),
        verified: parseVerified(params.verified),
        reset: parsePasswordReset(params.reset),
        joined: parseJoinedOrganization(params.joined),
        email: parseEmail(params.email),
    };
}
