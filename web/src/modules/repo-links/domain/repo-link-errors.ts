/** The `error` codes git-integration-service answers repository link requests with. */
export const REPO_LINK_NOT_FOUND = "REPO_LINK_NOT_FOUND";
/** The app already has an active link. */
export const REPO_LINK_EXISTS = "REPO_LINK_EXISTS";
/** One code for "the installation can't reach it" and "the caller can't", so neither is revealed. */
export const REPOSITORY_NOT_ACCESSIBLE = "REPOSITORY_NOT_ACCESSIBLE";
export const REPOSITORY_PERMISSION_TOO_LOW = "REPOSITORY_PERMISSION_TOO_LOW";
export const REPOSITORY_ARCHIVED = "REPOSITORY_ARCHIVED";
export const BRANCH_NOT_FOUND = "BRANCH_NOT_FOUND";
export const INVALID_ROOT_DIRECTORY = "INVALID_ROOT_DIRECTORY";
export const CONCURRENT_MODIFICATION = "CONCURRENT_MODIFICATION";
/** A manual build's key was already used for a different request. */
export const IDEMPOTENCY_KEY_REUSE = "IDEMPOTENCY_KEY_REUSE";
export const INSTALLATION_SUSPENDED = "INSTALLATION_SUSPENDED";
/** The installation is no longer linked to this organization. */
export const INSTALLATION_NOT_FOUND = "INSTALLATION_NOT_FOUND";
export const INSTALLATION_NOT_ACCESSIBLE = "INSTALLATION_NOT_ACCESSIBLE";
/** No GitHub session, or GitHub rejected the one there was. */
export const GITHUB_AUTHORIZATION_REQUIRED = "GITHUB_AUTHORIZATION_REQUIRED";
export const GITHUB_RATE_LIMITED = "GITHUB_RATE_LIMITED";
export const INSUFFICIENT_ROLE = "INSUFFICIENT_ROLE";
