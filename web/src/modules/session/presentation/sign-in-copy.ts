import { DEFAULT_RETRY_AFTER_SECONDS, TOO_MANY_REQUESTS } from "@/shared/domain/request-failure";
import { tooManyAttemptsCopy, type ErrorCopy } from "@/shared/presentation/errors";
import { EMAIL_NOT_VERIFIED, INVALID_CREDENTIALS } from "../domain/sign-in-failure";

export const SESSION_ENDED_COPY =
    "You were signed out, either because the session ended or because it was signed out from another device. " +
    "Sign in again to pick up where you left off.";

export const EMAIL_CONFIRMED_COPY = "Email confirmed.";

export const ACCOUNT_READY_COPY = "Your account is ready.";

export function openOrganizationCopy(orgName: string): string {
    return `Sign in to open ${orgName}.`;
}

export const PASSWORD_UPDATED_COPY = "Password updated.";
export const PASSWORD_UPDATED_DETAIL_COPY =
    "Sign in with your new password. Devices that were already signed in stay signed in; you can end those sessions under Your account.";

/** The same words for a wrong email and a wrong password: the backend doesn't say which, and neither do we. */
export const signInErrorCopy: ErrorCopy = {
    [INVALID_CREDENTIALS]: "Email or password is incorrect.",
    [EMAIL_NOT_VERIFIED]: "Confirm your email first.",
    [TOO_MANY_REQUESTS]: (error) => tooManyAttemptsCopy(error.retryAfterSeconds() ?? DEFAULT_RETRY_AFTER_SECONDS),
};
