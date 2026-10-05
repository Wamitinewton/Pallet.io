export const SLUG_HOST = "pallet.dev/";

export const EMAIL_TAKEN_COPY = "An account with this email already exists.";
export const SLUG_OR_EMAIL_TAKEN_COPY =
    "That organization URL or email is already in use. Change one of them and try again.";
export const SLUG_UNCHECKED_COPY = "We couldn't check this URL right now. You can still create the organization.";

export const CODE_HINT_COPY = "Letters and numbers, not case sensitive. You can paste the whole code.";
export const CODE_MISMATCH_COPY = "That code didn't match or has expired.";
export const CODE_RESENT_COPY = "We sent a new code. The old one no longer works.";

export const PASSWORD_HINT_COPY = "At least 12 characters. A short sentence is easier to remember than symbols.";

const STRENGTH_LABELS = ["Empty", "Weak", "Fair", "Good", "Strong"] as const;

export function strengthLabel(score: number): string {
    return STRENGTH_LABELS[score] ?? "Strong";
}

export function slugAvailableCopy(slug: string): string {
    return `${SLUG_HOST}${slug} is available`;
}

export function slugTakenCopy(slug: string): string {
    return `Someone already uses ${slug}.`;
}

export const RESET_LINK_HELP_COPY =
    "Nothing after a few minutes? Check spam, then make sure you typed the address you signed up with.";
export const RESET_LINK_EXPIRED_COPY =
    "Reset links work once and only for a short time. Ask for a new one and use it soon after it arrives.";
