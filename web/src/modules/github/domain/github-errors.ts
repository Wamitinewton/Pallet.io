/** The `error` codes git-integration-service answers GitHub requests with. */
export const INVALID_AUTHORIZATION_STATE = "INVALID_AUTHORIZATION_STATE";
export const GITHUB_SESSION_NOT_FOUND = "GITHUB_SESSION_NOT_FOUND";
/** No GitHub session, or GitHub rejected the one there was. */
export const GITHUB_AUTHORIZATION_REQUIRED = "GITHUB_AUTHORIZATION_REQUIRED";
export const INSTALLATION_NOT_ACCESSIBLE = "INSTALLATION_NOT_ACCESSIBLE";
export const INSTALLATION_SUSPENDED = "INSTALLATION_SUSPENDED";
/** Not linked to this organization. */
export const INSTALLATION_NOT_FOUND = "INSTALLATION_NOT_FOUND";
export const GITHUB_RATE_LIMITED = "GITHUB_RATE_LIMITED";
export const INSUFFICIENT_ROLE = "INSUFFICIENT_ROLE";
