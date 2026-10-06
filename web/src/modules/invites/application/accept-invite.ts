import type { Clock } from "@/shared/domain/clock";
import { ApiError } from "@/shared/domain/errors";
import type { OrgId } from "@/shared/domain/ids";
import {
    ACCOUNT_EXISTS,
    INVALID_TOKEN,
    INVITE_ALREADY_CONSUMED,
    INVITE_EMAIL_MISMATCH,
    INVITE_SIGN_IN_REQUIRED,
} from "../domain/invite-errors";
import { unavailableReason, type UnavailableReason } from "../domain/invite-preview";
import { parseInviteToken, readTokenHints, type InviteToken } from "../domain/invite-token";
import type { InviteAcceptanceRepository } from "./ports";

export type AcceptOutcome =
    /** `orgId` names the organization joined, when the link says which; the membership appears shortly after. */
    | { readonly kind: "accepted"; readonly orgId: OrgId | undefined }
    /** The invited address already has an account, which has to sign in to accept. */
    | { readonly kind: "sign-in-required" }
    /** The signed-in account isn't the one the invite was sent to. */
    | { readonly kind: "email-mismatch" }
    | { readonly kind: "unavailable"; readonly reason: UnavailableReason };

/** Field errors, rate limits and outages are thrown for the form to place; every other answer is an outcome. */
export type AcceptWithNewAccount = (token: string, password: string) => Promise<AcceptOutcome>;
export type AcceptAsSignedIn = (token: string) => Promise<AcceptOutcome>;

export interface AcceptInviteDependencies {
    readonly acceptance: InviteAcceptanceRepository;
    readonly clock: Clock;
}

function outcomeOf(error: unknown, token: InviteToken, clock: Clock): AcceptOutcome {
    if (!(error instanceof ApiError) || !error.isClientError()) throw error;
    switch (error.code) {
        case ACCOUNT_EXISTS:
        case INVITE_SIGN_IN_REQUIRED:
            return { kind: "sign-in-required" };
        case INVITE_EMAIL_MISMATCH:
            return { kind: "email-mismatch" };
        case INVITE_ALREADY_CONSUMED:
            return { kind: "unavailable", reason: "withdrawn" };
        case INVALID_TOKEN:
            return { kind: "unavailable", reason: unavailableReason(readTokenHints(token).expiresAt, clock.now()) };
        default:
            throw error;
    }
}

function accepting(
    clock: Clock,
    accept: (token: InviteToken) => Promise<void>,
): (value: string) => Promise<AcceptOutcome> {
    return async (value) => {
        const token = parseInviteToken(value);
        if (token === undefined) return { kind: "unavailable", reason: "withdrawn" };
        try {
            await accept(token);
            return { kind: "accepted", orgId: readTokenHints(token).orgId };
        } catch (error) {
            return outcomeOf(error, token, clock);
        }
    };
}

export function makeAcceptWithNewAccount({ acceptance, clock }: AcceptInviteDependencies): AcceptWithNewAccount {
    return (token, password) => accepting(clock, (parsed) => acceptance.acceptWithNewAccount(parsed, password))(token);
}

export function makeAcceptAsSignedIn({ acceptance, clock }: AcceptInviteDependencies): AcceptAsSignedIn {
    return accepting(clock, (parsed) => acceptance.acceptAsSignedIn(parsed));
}
