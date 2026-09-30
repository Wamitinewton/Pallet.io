package io.pallet.gitintegration.docs;

public final class ApiDocs {

    private static final String BASIS = ", judged from your current membership in this org, not your token's roles.";

    public static final String ANY_MEMBER = "Requires any active member of the organization" + BASIS;
    public static final String DEVELOPER = "Requires DEVELOPER or above" + BASIS;
    public static final String ADMIN = "Requires ADMIN or above" + BASIS;
    public static final String ANY_ACCOUNT = "Requires any signed-in account; no organization membership is involved, "
            + "and the session belongs to the caller whichever org is open.";
    public static final String SESSION = " Needs a GitHub user session. Without one it answers "
            + "403 GITHUB_AUTHORIZATION_REQUIRED; start one with POST /github/authorizations and retry. A user who "
            + "authorized before goes through GitHub without a prompt.";
    public static final String NO_SESSION = " Needs no GitHub user session.";

    public static final String MEMBERSHIP_404 = "ORG_NOT_FOUND";
    public static final String MEMBERSHIP_403 = "NOT_A_MEMBER or INSUFFICIENT_ROLE";
    public static final String GITHUB_502 = "EXTERNAL_SERVICE_ERROR: GitHub failed after retries, or its breaker is "
            + "open. GITHUB_REQUEST_REJECTED or GITHUB_CREDENTIAL_REJECTED: GitHub refused the call";
    public static final String GITHUB_503 = "GITHUB_RATE_LIMITED: the installation's GitHub budget is spent; wait "
            + "meta.retryAfter seconds. SERVICE_UNAVAILABLE when the database is unreachable";

    public static final String BRANCH_PATTERN = "^(?!-)(?!/)(?!.*/$)(?!.*\\.$)(?!.*\\.\\.)(?!.*//)(?!.*@\\{)(?!@$)"
            + "(?!(.*/)?\\.)(?!.*\\.lock(/|$))[^\\x00-\\x20\\x7f~^:?*\\[\\\\]{1,255}$";
    public static final String BRANCH_RULES = "A Git branch name: at most 255 bytes, no spaces, control characters or "
            + "any of ~^:?*[\\, no '..', '@{' or '//', not starting with '-' or '/', not ending with '/' or '.', and no "
            + "component starting with '.' or ending with '.lock'.";
    public static final String ROOT_DIRECTORY_PATTERN =
            "^$|^(?!/)(?!.*\\.\\.)(?!.*//)(?!(.*/)?\\.(/|$))[^\\\\\\x00-\\x1f\\x7f-\\x9f]{1,255}$";
    public static final String ROOT_DIRECTORY_RULES = "A path relative to the repository root, at most 255 "
            + "characters, with no '..', no '.' or empty segment, no backslash and no control character; a trailing "
            + "'/' is dropped. Empty means the repository root. Anything else is 400 INVALID_ROOT_DIRECTORY.";

    private ApiDocs() {}
}
