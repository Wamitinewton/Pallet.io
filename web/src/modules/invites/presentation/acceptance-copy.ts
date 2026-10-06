import { INVITE_LIMITS } from "../domain/invite";

export const INVITED_EYEBROW_COPY = "You're invited";
export const SET_PASSWORD_TITLE_COPY = "Set a password to finish";
export const SET_PASSWORD_LEDE_COPY =
    "We'll create your Pallet account with the invited email. You'll use it to sign in from now on.";
export const PASSWORD_PLACEHOLDER_COPY = "At least 12 characters";
export const ACCEPT_AND_JOIN_COPY = "Accept and join";
export const HAVE_AN_ACCOUNT_COPY = "Already have a Pallet account?";
export const SIGN_IN_TO_ACCEPT_COPY = "Sign in to accept";
export const SIGN_OUT_COPY = "Sign out";
export const LOADING_INVITE_COPY = "Loading your invite";
export const PREVIEW_FAILED_TITLE_COPY = "We couldn't open this invite";
export const EXPIRED_TITLE_COPY = "This invite has expired";
export const WITHDRAWN_TITLE_COPY = "This invite is no longer valid";
export const WITHDRAWN_LEDE_COPY =
    "It was withdrawn, or it has already been used. If you think that's a mistake, contact the person who invited you.";
export const VISIT_PALLET_COPY = "Go to pallet.dev";
export const SIGN_IN_COPY = "Sign in";
export const OPEN_PALLET_COPY = "Go to your organizations";
export const WHAT_IS_PALLET_COPY = "What is Pallet?";
export const TRY_AGAIN_COPY = "Try again";

const expiryFormat = new Intl.DateTimeFormat("en-GB", {
    weekday: "long",
    day: "numeric",
    month: "long",
    hour: "2-digit",
    minute: "2-digit",
});

export function formatInviteExpiry(date: Date): string {
    return expiryFormat.format(date);
}

export function joinTitle(orgName: string): string {
    return `Join ${orgName}`;
}

export function expiredLede(inviterName: string | undefined): string {
    const who = inviterName ?? "the person who invited you";
    return `Invites stay open for ${String(INVITE_LIMITS.ttlDays)} days. Ask ${who} or another admin to send you a new one.`;
}

export function accountExistsCopy(orgName: string): string {
    return `This email already has a Pallet account. Sign in, then open the invite link again to join. ${orgName} will show up in your organization switcher.`;
}

export function signInToJoinCopy(orgName: string): string {
    return `This email already has a Pallet account. Sign in with it and you'll come straight back here to join ${orgName}.`;
}

export function signedInElsewhereCopy(email: string | undefined, orgName: string): string {
    const who = email === undefined ? "You're signed in" : `You're signed in as ${email}`;
    return `${who}. Joining ${orgName} with an account you already have isn't available yet. If the invite was sent to another address, sign out to accept it with a new account.`;
}

export function joinAsCopy(orgName: string, email: string | undefined): string {
    return email === undefined ? joinTitle(orgName) : `Join ${orgName} as ${email}`;
}

export function emailMismatchCopy(maskedEmail: string): string {
    return `This invite was sent to ${maskedEmail}. Sign out and sign in with that account.`;
}

export function joiningCopy(orgName: string): string {
    return `Joining ${orgName}. You'll be taken there as soon as it's ready.`;
}

export function joiningSlowCopy(orgName: string): string {
    return `You've joined ${orgName}, but it's taking longer than usual to show up. Give it a moment, then try again.`;
}
