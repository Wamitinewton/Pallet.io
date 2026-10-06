/** The `error` codes org-team-service answers invite requests with. */
export const ALREADY_A_MEMBER = "ALREADY_A_MEMBER";
export const MEMBER_PREVIOUSLY_REMOVED = "MEMBER_PREVIOUSLY_REMOVED";
export const INVITE_ALREADY_PENDING = "INVITE_ALREADY_PENDING";
export const QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
export const PERSONAL_ORG_IMMUTABLE = "PERSONAL_ORG_IMMUTABLE";
export const INVITE_NOT_PENDING = "INVITE_NOT_PENDING";
/** Also what revoking answers for an invite that is no longer pending. */
export const INVITE_NOT_FOUND = "INVITE_NOT_FOUND";
export const INSUFFICIENT_ROLE = "INSUFFICIENT_ROLE";

export const NO_LONGER_PENDING: readonly string[] = [INVITE_NOT_PENDING, INVITE_NOT_FOUND];

/** The token's signature, purpose or expiry failed; both the preview and accepting answer it. */
export const INVALID_TOKEN = "INVALID_TOKEN";
/** The preview's answer for a revoked, accepted or expired invite, or one whose organization is gone. */
export const INVITE_NO_LONGER_VALID = "INVITE_NO_LONGER_VALID";
export const INVITE_ALREADY_CONSUMED = "INVITE_ALREADY_CONSUMED";
/** Accepting as a new account when the invited address already has one, until identity-service/15 ships. */
export const ACCOUNT_EXISTS = "CONFLICT";
export const INVITE_SIGN_IN_REQUIRED = "INVITE_SIGN_IN_REQUIRED";
export const INVITE_EMAIL_MISMATCH = "INVITE_EMAIL_MISMATCH";
