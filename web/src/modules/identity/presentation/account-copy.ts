export const PROFILE_DESCRIPTION_COPY = "Your name appears to everyone in your organizations.";
export const EMAIL_CONFIRMED_COPY = "Confirmed. This is also the address you sign in with.";
export const EMAIL_FIXED_COPY = "Email changes aren't available yet.";
export const PROFILE_SAVED_COPY = "Profile updated";
export const PROFILE_NOT_SAVED_COPY = "Your name wasn't saved.";

export const PASSWORD_DESCRIPTION_COPY = "We check your current password before saving the new one.";
export const WRONG_CURRENT_PASSWORD_COPY = "That isn't your current password.";
export const PASSWORD_CHANGED_COPY = "Password changed";
export const NEW_PASSWORD_HINT_COPY = "At least 12 characters.";

export const SESSIONS_TITLE_COPY = "Where you're signed in";
export const SESSIONS_DESCRIPTION_COPY = "Sign out of anything you don't recognize, then change your password.";
export const SESSIONS_FOOT_COPY =
    "A signed-out session stops working on its very next request, even if a tab is still open.";
export const ONLY_THIS_DEVICE_COPY = "You aren't signed in anywhere else.";
export const UNKNOWN_ADDRESS_COPY = "Unknown address";
export const SESSION_ENDED_COPY = "Session signed out";
export const SESSION_NOT_ENDED_COPY = "That session is still signed in.";
export const OTHERS_NOT_ENDED_COPY = "Your other sessions are still signed in.";

export function otherSessionsCopy(count: number): string {
    return count === 1 ? "1 other session" : `${String(count)} other sessions`;
}

export function signOutOthersLabel(count: number): string {
    return count === 1 ? "Sign out 1 session" : `Sign out ${String(count)} sessions`;
}

export function othersEndedCopy(count: number): string {
    return `Signed out of ${otherSessionsCopy(count)}`;
}

export function sessionLabel(ipAddress: string | undefined): string {
    return ipAddress === undefined ? "the session from an unknown address" : `the session from ${ipAddress}`;
}

export function endSessionDescription(ipAddress: string | undefined): string {
    const label = sessionLabel(ipAddress);
    return `${label.charAt(0).toUpperCase()}${label.slice(1)} stops working on its next request. You stay signed in here.`;
}

export function endOtherSessionsDescription(count: number): string {
    return `${otherSessionsCopy(count)} ${count === 1 ? "ends" : "end"} right away. You stay signed in here.`;
}
