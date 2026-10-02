export interface paths {
    "/github/authorizations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Start GitHub authorization
         * @description Returns authorizeUrl, GitHub's user authorization page with a signed, single-use state bound to the caller, and when that state expires. Send the user there; GitHub redirects back with code and state, which go to POST /github/authorizations/complete. A user who authorized before is sent straight back without a prompt. Requires any signed-in account; no organization membership is involved, and the session belongs to the caller whichever org is open.
         */
        post: operations["start"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/github/authorizations/complete": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Complete GitHub authorization
         * @description Takes code and state from GitHub's redirect, exchanges the code and stores the session. Each state completes once; start again after a 400. Requires any signed-in account; no organization membership is involved, and the session belongs to the caller whichever org is open.
         */
        post: operations["complete"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/github/installations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List installations the caller's GitHub user can see
         * @description What the dashboard offers when an org links an installation that already exists: pick one here, then call POST /orgs/{orgId}/github/installations with {installationId} alone. Each entry says whether the app is suspended on that account. Requires any signed-in account; no organization membership is involved, and the session belongs to the caller whichever org is open. Needs a GitHub user session. Without one it answers 403 GITHUB_AUTHORIZATION_REQUIRED; start one with POST /github/authorizations and retry. A user who authorized before goes through GitHub without a prompt.
         */
        get: operations["installations"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/github/session": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Get the caller's GitHub session
         * @description The GitHub login and when the session expires. Requires any signed-in account; no organization membership is involved, and the session belongs to the caller whichever org is open.
         */
        get: operations["session"];
        put?: never;
        post?: never;
        /**
         * End the caller's GitHub session
         * @description Forgets the session on Pallet's side; the app stays authorized on GitHub, so the next authorization needs no prompt. Ending a session that doesn't exist is also a 204. Requires any signed-in account; no organization membership is involved, and the session belongs to the caller whichever org is open.
         */
        delete: operations["end"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/orgs/{orgId}/apps/{appId}/repo-link": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Get the app's repository link
         * @description The link, its status and disconnect reason, the last accepted head, who verified access and when it was last confirmed, and warnings such as REPOSITORY_ARCHIVED. Requires any active member of the organization, judged from your current membership in this org, not your token's roles. Needs no GitHub user session.
         */
        get: operations["get"];
        /**
         * Link a repository to the app
         * @description Links the app to a repository in one of this org's installations. The caller must hold at least push on it on GitHub, checked with their own token. The branch defaults to the repository's default branch and must exist. autoDeploy and deployNow are separate on purpose: autoDeploy says whether future pushes build, deployNow whether to build the current head right away. The dashboard's import flow sends deployNow true; linking a repository to test the connection gets no deploy. Requires ADMIN or above, judged from your current membership in this org, not your token's roles. Needs a GitHub user session. Without one it answers 403 GITHUB_AUTHORIZATION_REQUIRED; start one with POST /github/authorizations and retry. A user who authorized before goes through GitHub without a prompt.
         */
        put: operations["link"];
        post?: never;
        /**
         * Disconnect the repository link
         * @description Sets the link DISCONNECTED with reason UNLINKED_BY_USER; pushes stop building. Requires ADMIN or above, judged from your current membership in this org, not your token's roles. Needs no GitHub user session.
         */
        delete: operations["disconnect"];
        options?: never;
        head?: never;
        /**
         * Tune the repository link
         * @description Changes productionBranch, rootDirectory or autoDeploy, with the version from the last read. An absent field is unchanged; an empty rootDirectory builds from the repository root. A new branch must exist on GitHub. Requires DEVELOPER or above, judged from your current membership in this org, not your token's roles. Needs no GitHub user session.
         */
        patch: operations["update"];
        trace?: never;
    };
    "/orgs/{orgId}/apps/{appId}/repo-link/builds": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Build the branch's current head
         * @description Resolves the branch's head on GitHub and asks for a build of it; the branch defaults to the production branch and a commit can't be named. A retry with the same Idempotency-Key and request returns the first result without calling GitHub or counting against the limit. Limited per app per hour. Requires DEVELOPER or above, judged from your current membership in this org, not your token's roles. Needs no GitHub user session.
         */
        post: operations["build"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/orgs/{orgId}/apps/{appId}/repo-link/verification": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Take over verifying the link
         * @description Makes the caller the link's verifier after the same access check as linking, for handing over before the current verifier leaves the GitHub organization. A caller who fails the check changes nothing. Requires ADMIN or above, judged from your current membership in this org, not your token's roles. Needs a GitHub user session. Without one it answers 403 GITHUB_AUTHORIZATION_REQUIRED; start one with POST /github/authorizations and retry. A user who authorized before goes through GitHub without a prompt.
         */
        post: operations["takeOverVerification"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/orgs/{orgId}/github/install-sessions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Start a GitHub App install
         * @description Returns installUrl, GitHub's page for installing the app with a signed, single-use state bound to this org and the caller, and when the state expires (ten minutes). After the install, GitHub redirects back with installation_id, code and state for POST installations. Requires ADMIN or above, judged from your current membership in this org, not your token's roles. Needs no GitHub user session.
         */
        post: operations["startInstall"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/orgs/{orgId}/github/installations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List the org's installations
         * @description The org's linked installations and their status on GitHub, newest link first; sort is ignored. Requires any active member of the organization, judged from your current membership in this org, not your token's roles. Needs no GitHub user session.
         */
        get: operations["list"];
        put?: never;
        /**
         * Link an installation to the org
         * @description Either {installationId, code, state} straight from a fresh install's setup redirect, which also creates the caller's GitHub session, or {installationId} alone for an installation that already exists, which needs a session. Both check with the caller's own GitHub token that they can see the installation. 201 with the link, 200 when this org already had it linked. Requires ADMIN or above, judged from your current membership in this org, not your token's roles. Needs a GitHub user session. Without one it answers 403 GITHUB_AUTHORIZATION_REQUIRED; start one with POST /github/authorizations and retry. A user who authorized before goes through GitHub without a prompt. The fresh-install form brings its own session.
         */
        post: operations["link_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/orgs/{orgId}/github/installations/{installationId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /**
         * Unlink an installation from the org
         * @description Unlinks it from this org only and disconnects this org's repository links that used it; other orgs keep theirs. The app stays installed on GitHub. Requires ADMIN or above, judged from your current membership in this org, not your token's roles. Needs no GitHub user session.
         */
        delete: operations["unlink"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/orgs/{orgId}/github/installations/{installationId}/repositories": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List repositories the caller can link
         * @description The repository picker. Lists only repositories in this installation that the caller can reach on GitHub, with the caller's own permission and whether PUT repo-link would accept it; never the installation's full list. Requires DEVELOPER or above, judged from your current membership in this org, not your token's roles. Needs a GitHub user session. Without one it answers 403 GITHUB_AUTHORIZATION_REQUIRED; start one with POST /github/authorizations and retry. A user who authorized before goes through GitHub without a prompt.
         */
        get: operations["repositories"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/webhooks/github": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Receive a GitHub webhook delivery
         * @description Called by GitHub only. Authenticated by the X-Hub-Signature-256 HMAC over the raw body, never by a bearer token, so it takes none. The delivery is stored and acknowledged at once and processed afterwards; a redelivery of a stored GUID changes nothing. The payload is GitHub's own event, documented by GitHub.
         */
        post: operations["receive"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        ApiResponseAuthorizationStartResponse: {
            data?: components["schemas"]["AuthorizationStartResponse"];
            message?: string;
            success?: boolean;
        };
        ApiResponseGitHubSessionResponse: {
            data?: components["schemas"]["GitHubSessionResponse"];
            message?: string;
            success?: boolean;
        };
        ApiResponseInstallationLinkResponse: {
            data?: components["schemas"]["InstallationLinkResponse"];
            message?: string;
            success?: boolean;
        };
        ApiResponseInstallSessionResponse: {
            data?: components["schemas"]["InstallSessionResponse"];
            message?: string;
            success?: boolean;
        };
        ApiResponseManualBuildResultDto: {
            data?: components["schemas"]["ManualBuildResultDto"];
            message?: string;
            success?: boolean;
        };
        ApiResponsePageResponseGitHubInstallationResponse: {
            data?: components["schemas"]["PageResponseGitHubInstallationResponse"];
            message?: string;
            success?: boolean;
        };
        ApiResponsePageResponseInstallationLinkResponse: {
            data?: components["schemas"]["PageResponseInstallationLinkResponse"];
            message?: string;
            success?: boolean;
        };
        ApiResponsePageResponsePickerRepositoryResponse: {
            data?: components["schemas"]["PageResponsePickerRepositoryResponse"];
            message?: string;
            success?: boolean;
        };
        ApiResponseRepoLinkDto: {
            data?: components["schemas"]["RepoLinkDto"];
            message?: string;
            success?: boolean;
        };
        ApiResponseVoid: {
            data?: unknown;
            message?: string;
            success?: boolean;
        };
        /** @description The state inside authorizeUrl is the only one ever returned; it works once, for this caller. */
        AuthorizationStartResponse: {
            /** @example https://github.com/login/oauth/authorize?client_id=example-client&state=example-state */
            authorizeUrl?: string;
            /**
             * Format: date-time
             * @description When the state in authorizeUrl expires.
             */
            expiresAt?: string;
        };
        /** @description code and state from GitHub's redirect; each works once and neither is ever returned. */
        CompleteAuthorizationRequest: {
            /** @description code from GitHub's redirect. */
            code: string;
            /** @description state from GitHub's redirect. */
            state: string;
        };
        ErrorResponse: {
            error?: string;
            message?: string;
            meta?: {
                [key: string]: unknown;
            };
            path?: string;
            /** Format: int32 */
            statusCode?: number;
            success?: boolean;
            /** Format: date-time */
            timestamp?: string;
            validationErrors?: components["schemas"]["ValidationError"][];
        };
        GitHubInstallationResponse: {
            /** @example example-org */
            accountLogin?: string;
            /** @enum {string} */
            accountType?: "User" | "Organization";
            /**
             * Format: int64
             * @example 41000001
             */
            installationId?: number;
            /** @description Suspended on GitHub; linking it is refused until it is unsuspended. */
            suspended?: boolean;
        };
        /** @description The caller's GitHub session. Never returned: the GitHub token, a code, or a state; the only state ever returned is the one inside authorizeUrl or installUrl. */
        GitHubSessionResponse: {
            /**
             * Format: date-time
             * @description After this the session is gone and GitHub authorization starts again.
             */
            expiresAt?: string;
            /** @example octo-example */
            githubLogin?: string;
        };
        Head: {
            /** Format: date-time */
            advancedAt?: string;
            branch?: string;
            sha?: string;
        };
        InstallationLinkResponse: {
            /** @example example-org */
            accountLogin?: string;
            /** @enum {string} */
            accountType?: "User" | "Organization";
            /**
             * Format: int64
             * @example 41000001
             */
            installationId?: number;
            /** Format: date-time */
            linkedAt?: string;
            linkedByUserId?: string;
            /** @enum {string} */
            status?: "ACTIVE" | "SUSPENDED";
        };
        /** @description The state inside installUrl is the only one ever returned; it works once, for this org and caller. */
        InstallSessionResponse: {
            /**
             * Format: date-time
             * @description When the state in installUrl expires.
             */
            expiresAt?: string;
            /** @example https://github.com/apps/pallet/installations/new?state=example-state */
            installUrl?: string;
        };
        /** @description Either all three fields from a fresh install's setup redirect, or installationId alone for an installation that already exists. code and state go together or not at all. */
        LinkInstallationRequest: {
            /** @description code from GitHub's setup redirect; used once and never returned. */
            code?: string | null;
            /**
             * Format: int64
             * @description installation_id from GitHub's setup redirect, or from GET /github/installations.
             * @example 41000001
             */
            installationId: number;
            /** @description state from GitHub's setup redirect; used once and never returned. */
            state?: string | null;
        };
        /** @description Optional. An empty or absent body builds the production branch's head. */
        ManualBuildRequestDto: {
            /**
             * @description Defaults to the production branch. A Git branch name: at most 255 bytes, no spaces, control characters or any of ~^:?*[\, no '..', '@{' or '//', not starting with '-' or '/', not ending with '/' or '.', and no component starting with '.' or ending with '.lock'.
             * @example main
             */
            branch?: string | null;
        };
        ManualBuildResultDto: {
            /** @example main */
            branch?: string;
            /** @example 0123456789abcdef0123456789abcdef01234567 */
            commitSha?: string;
            /**
             * Format: uuid
             * @description Names the build request; a retry with the same key returns the same id.
             */
            eventId?: string;
        };
        PageResponseGitHubInstallationResponse: {
            content?: components["schemas"]["GitHubInstallationResponse"][];
            first?: boolean;
            last?: boolean;
            /** Format: int32 */
            page?: number;
            /** Format: int32 */
            size?: number;
            /** Format: int64 */
            totalElements?: number;
            /** Format: int32 */
            totalPages?: number;
        };
        PageResponseInstallationLinkResponse: {
            content?: components["schemas"]["InstallationLinkResponse"][];
            first?: boolean;
            last?: boolean;
            /** Format: int32 */
            page?: number;
            /** Format: int32 */
            size?: number;
            /** Format: int64 */
            totalElements?: number;
            /** Format: int32 */
            totalPages?: number;
        };
        PageResponsePickerRepositoryResponse: {
            content?: components["schemas"]["PickerRepositoryResponse"][];
            first?: boolean;
            last?: boolean;
            /** Format: int32 */
            page?: number;
            /** Format: int32 */
            size?: number;
            /** Format: int64 */
            totalElements?: number;
            /** Format: int32 */
            totalPages?: number;
        };
        /** @description Tunes a link without choosing another repository. At least one of productionBranch, rootDirectory and autoDeploy is required; any other field, installationId and repoId included, is a 400. */
        PatchRepoLinkRequest: {
            /** @description Whether future pushes to the production branch build. */
            autoDeploy?: boolean | null;
            /**
             * @description Must exist on GitHub. A Git branch name: at most 255 bytes, no spaces, control characters or any of ~^:?*[\, no '..', '@{' or '//', not starting with '-' or '/', not ending with '/' or '.', and no component starting with '.' or ending with '.lock'.
             * @example release
             */
            productionBranch?: string | null;
            /**
             * @description A path relative to the repository root, at most 255 characters, with no '..', no '.' or empty segment, no backslash and no control character; a trailing '/' is dropped. Empty means the repository root. Anything else is 400 INVALID_ROOT_DIRECTORY.
             * @example services/api
             */
            rootDirectory?: string | null;
            /**
             * Format: int64
             * @description The link's version from the last read; a stale one is 409.
             * @example 3
             */
            version: number;
        };
        PickerRepositoryResponse: {
            archived?: boolean;
            /** @example main */
            defaultBranch?: string;
            /** @example example-org/web */
            fullName?: string;
            /** @description Whether PUT repo-link would accept it: push or above, and not archived. */
            linkable?: boolean;
            /**
             * @description The caller's own highest permission on GitHub, or null for none.
             * @enum {string|null}
             */
            permission?: "admin" | "maintain" | "push" | "triage" | "pull" | null;
            private?: boolean;
            /**
             * Format: int64
             * @example 72000001
             */
            repoId?: number;
        };
        /** @description Chooses the repository an app deploys from. Any field not listed here is a 400. */
        PutRepoLinkRequest: {
            /**
             * @description Whether future pushes to the production branch build.
             * @default true
             */
            autoDeploy: boolean;
            /**
             * @description Whether to build the branch's current head right away.
             * @default false
             */
            deployNow: boolean;
            /**
             * Format: int64
             * @description An installation linked to this org.
             * @example 41000001
             */
            installationId: number;
            /**
             * @description Defaults to the repository's default branch. A Git branch name: at most 255 bytes, no spaces, control characters or any of ~^:?*[\, no '..', '@{' or '//', not starting with '-' or '/', not ending with '/' or '.', and no component starting with '.' or ending with '.lock'.
             * @example main
             */
            productionBranch?: string | null;
            /**
             * Format: int64
             * @description GitHub's numeric repository id, from the repository picker.
             * @example 72000001
             */
            repoId: number;
            /**
             * @description A path relative to the repository root, at most 255 characters, with no '..', no '.' or empty segment, no backslash and no control character; a trailing '/' is dropped. Empty means the repository root. Anything else is 400 INVALID_ROOT_DIRECTORY.
             * @example apps/web
             */
            rootDirectory?: string | null;
        };
        /** @description An app's repository link. */
        RepoLinkDto: {
            /** Format: uuid */
            appId?: string;
            autoDeploy?: boolean;
            /** Format: date-time */
            createdAt?: string;
            /** Format: date-time */
            disconnectedAt?: string;
            disconnectReason?: string | null;
            /** Format: int64 */
            installationId?: number;
            lastAcceptedHead?: components["schemas"]["Head"];
            productionBranch?: string;
            repoFullName?: string;
            /** Format: int64 */
            repoId?: number;
            rootDirectory?: string;
            /** @enum {string} */
            status?: "ACTIVE" | "DISCONNECTED";
            /** Format: date-time */
            updatedAt?: string;
            verification?: components["schemas"]["Verification"];
            /**
             * Format: int64
             * @description Send it back on PATCH.
             */
            version?: number;
            /** @description Codes for an active link that still works but needs attention, such as REPOSITORY_ARCHIVED. */
            warnings?: string[];
        };
        ValidationError: {
            field?: string;
            message?: string;
        };
        Verification: {
            /** Format: date-time */
            accessCheckedAt?: string;
            /** Format: date-time */
            accessVerifiedAt?: string;
            githubLogin?: string;
            /** @enum {string} */
            permission?: "push" | "maintain" | "admin";
            verifiedByUserId?: string;
        };
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    start: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Authorization started */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseAuthorizationStartResponse"];
                };
            };
            /** @description Validation or malformed-request failure */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Missing or invalid credentials */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Authenticated but not authorized for this resource */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Resource not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Conflict with current resource state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Rate limit exceeded */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Unexpected server error */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    complete: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CompleteAuthorizationRequest"];
            };
        };
        responses: {
            /** @description Session stored */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseGitHubSessionResponse"];
                };
            };
            /** @description INVALID_AUTHORIZATION_STATE: the state is forged, expired, already used or for another user, or GitHub rejected the code. Also a missing code or state */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Missing or invalid credentials */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Authenticated but not authorized for this resource */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Resource not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Conflict with current resource state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Rate limit exceeded */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Unexpected server error */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description EXTERNAL_SERVICE_ERROR: GitHub failed after retries, or its breaker is open. GITHUB_REQUEST_REJECTED or GITHUB_CREDENTIAL_REJECTED: GitHub refused the call */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    installations: {
        parameters: {
            query?: {
                page?: number;
                size?: number;
                sort?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description One page of installations */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponsePageResponseGitHubInstallationResponse"];
                };
            };
            /** @description Validation or malformed-request failure */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Missing or invalid credentials */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description GITHUB_AUTHORIZATION_REQUIRED */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Resource not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Conflict with current resource state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Rate limit exceeded */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Unexpected server error */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description EXTERNAL_SERVICE_ERROR: GitHub failed after retries, or its breaker is open. GITHUB_REQUEST_REJECTED or GITHUB_CREDENTIAL_REJECTED: GitHub refused the call */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    session: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The session */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseGitHubSessionResponse"];
                };
            };
            /** @description Validation or malformed-request failure */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Missing or invalid credentials */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Authenticated but not authorized for this resource */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description GITHUB_SESSION_NOT_FOUND: the caller has no session, or it expired */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Conflict with current resource state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Rate limit exceeded */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Unexpected server error */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    end: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Ended */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Validation or malformed-request failure */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Missing or invalid credentials */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Authenticated but not authorized for this resource */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Resource not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Conflict with current resource state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Rate limit exceeded */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Unexpected server error */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    get: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                appId: string;
                orgId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The link */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseRepoLinkDto"];
                };
            };
            /** @description Validation or malformed-request failure */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Missing or invalid credentials */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description NOT_A_MEMBER or INSUFFICIENT_ROLE */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description APP_NOT_FOUND, REPO_LINK_NOT_FOUND, ORG_NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Conflict with current resource state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Rate limit exceeded */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Unexpected server error */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    link: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                appId: string;
                orgId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PutRepoLinkRequest"];
            };
        };
        responses: {
            /** @description Linked */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseRepoLinkDto"];
                };
            };
            /** @description INVALID_ROOT_DIRECTORY, an invalid productionBranch, or an unknown field */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Missing or invalid credentials */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description REPOSITORY_NOT_ACCESSIBLE, REPOSITORY_PERMISSION_TOO_LOW, GITHUB_AUTHORIZATION_REQUIRED, NOT_A_MEMBER or INSUFFICIENT_ROLE */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description APP_NOT_FOUND, INSTALLATION_NOT_FOUND, ORG_NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description REPO_LINK_EXISTS: the app already has an active link. INSTALLATION_SUSPENDED */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description REPOSITORY_ARCHIVED or BRANCH_NOT_FOUND */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Rate limit exceeded */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Unexpected server error */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description EXTERNAL_SERVICE_ERROR: GitHub failed after retries, or its breaker is open. GITHUB_REQUEST_REJECTED or GITHUB_CREDENTIAL_REJECTED: GitHub refused the call */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description GITHUB_RATE_LIMITED: the installation's GitHub budget is spent; wait meta.retryAfter seconds. SERVICE_UNAVAILABLE when the database is unreachable */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    disconnect: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                appId: string;
                orgId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Disconnected */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Validation or malformed-request failure */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Missing or invalid credentials */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description NOT_A_MEMBER or INSUFFICIENT_ROLE */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description APP_NOT_FOUND, REPO_LINK_NOT_FOUND, ORG_NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Conflict with current resource state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Rate limit exceeded */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Unexpected server error */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    update: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                appId: string;
                orgId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PatchRepoLinkRequest"];
            };
        };
        responses: {
            /** @description Updated */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseRepoLinkDto"];
                };
            };
            /** @description INVALID_ROOT_DIRECTORY, an invalid productionBranch, no field to change, or any other field */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Missing or invalid credentials */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description NOT_A_MEMBER or INSUFFICIENT_ROLE */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description APP_NOT_FOUND, REPO_LINK_NOT_FOUND, ORG_NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description CONCURRENT_MODIFICATION: version is stale */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description BRANCH_NOT_FOUND */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Rate limit exceeded */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Unexpected server error */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description EXTERNAL_SERVICE_ERROR: GitHub failed after retries, or its breaker is open. GITHUB_REQUEST_REJECTED or GITHUB_CREDENTIAL_REJECTED: GitHub refused the call */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description GITHUB_RATE_LIMITED: the installation's GitHub budget is spent; wait meta.retryAfter seconds. SERVICE_UNAVAILABLE when the database is unreachable */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    build: {
        parameters: {
            query?: never;
            header: {
                /** @description Names this request; a retry with the same key returns the first result. */
                "Idempotency-Key": string;
            };
            path: {
                appId: string;
                orgId: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["ManualBuildRequestDto"];
            };
        };
        responses: {
            /** @description Requested, or the first result of an earlier request with this key */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseManualBuildResultDto"];
                };
            };
            /** @description Idempotency-Key missing or not 1 to 128 of [A-Za-z0-9_-], an invalid branch, or an unknown field */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Missing or invalid credentials */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description NOT_A_MEMBER or INSUFFICIENT_ROLE */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description APP_NOT_FOUND, REPO_LINK_NOT_FOUND, INSTALLATION_NOT_FOUND, ORG_NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description IDEMPOTENCY_KEY_REUSE: the key was used for a different request. INSTALLATION_SUSPENDED. CONCURRENT_MODIFICATION */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description BRANCH_NOT_FOUND */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description TOO_MANY_REQUESTS: the app's manual build limit is reached; wait meta.retryAfter seconds */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Unexpected server error */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description EXTERNAL_SERVICE_ERROR: GitHub failed after retries, or its breaker is open. GITHUB_REQUEST_REJECTED or GITHUB_CREDENTIAL_REJECTED: GitHub refused the call */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description GITHUB_RATE_LIMITED: the installation's GitHub budget is spent; wait meta.retryAfter seconds. SERVICE_UNAVAILABLE when the database is unreachable */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    takeOverVerification: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                appId: string;
                orgId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The caller is now the verifier */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseRepoLinkDto"];
                };
            };
            /** @description Validation or malformed-request failure */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Missing or invalid credentials */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description REPOSITORY_NOT_ACCESSIBLE, REPOSITORY_PERMISSION_TOO_LOW, GITHUB_AUTHORIZATION_REQUIRED, NOT_A_MEMBER or INSUFFICIENT_ROLE */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description APP_NOT_FOUND, REPO_LINK_NOT_FOUND, INSTALLATION_NOT_FOUND, ORG_NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description CONCURRENT_MODIFICATION: the link moved to another repository during the check. INSTALLATION_SUSPENDED */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description REPOSITORY_ARCHIVED or BRANCH_NOT_FOUND */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Rate limit exceeded */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Unexpected server error */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description EXTERNAL_SERVICE_ERROR: GitHub failed after retries, or its breaker is open. GITHUB_REQUEST_REJECTED or GITHUB_CREDENTIAL_REJECTED: GitHub refused the call */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description GITHUB_RATE_LIMITED: the installation's GitHub budget is spent; wait meta.retryAfter seconds. SERVICE_UNAVAILABLE when the database is unreachable */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    startInstall: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Install session started */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseInstallSessionResponse"];
                };
            };
            /** @description Validation or malformed-request failure */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Missing or invalid credentials */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description NOT_A_MEMBER or INSUFFICIENT_ROLE */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description ORG_NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Conflict with current resource state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Rate limit exceeded */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Unexpected server error */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    list: {
        parameters: {
            query?: {
                page?: number;
                size?: number;
                sort?: string;
            };
            header?: never;
            path: {
                orgId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description One page of linked installations */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponsePageResponseInstallationLinkResponse"];
                };
            };
            /** @description Validation or malformed-request failure */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Missing or invalid credentials */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description NOT_A_MEMBER or INSUFFICIENT_ROLE */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description ORG_NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Conflict with current resource state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Rate limit exceeded */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Unexpected server error */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    link_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["LinkInstallationRequest"];
            };
        };
        responses: {
            /** @description Already linked to this org */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseInstallationLinkResponse"];
                };
            };
            /** @description Linked */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseInstallationLinkResponse"];
                };
            };
            /** @description INVALID_AUTHORIZATION_STATE, or code and state not sent together */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Missing or invalid credentials */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description INSTALLATION_NOT_ACCESSIBLE, GITHUB_AUTHORIZATION_REQUIRED, NOT_A_MEMBER or INSUFFICIENT_ROLE */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description ORG_NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description INSTALLATION_SUSPENDED: the app is suspended on that GitHub account */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Rate limit exceeded */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Unexpected server error */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description EXTERNAL_SERVICE_ERROR: GitHub failed after retries, or its breaker is open. GITHUB_REQUEST_REJECTED or GITHUB_CREDENTIAL_REJECTED: GitHub refused the call */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description GITHUB_RATE_LIMITED: the installation's GitHub budget is spent; wait meta.retryAfter seconds. SERVICE_UNAVAILABLE when the database is unreachable */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    unlink: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                installationId: number;
                orgId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Unlinked */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Validation or malformed-request failure */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Missing or invalid credentials */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description NOT_A_MEMBER or INSUFFICIENT_ROLE */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description INSTALLATION_NOT_FOUND: not linked to this org. ORG_NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Conflict with current resource state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Rate limit exceeded */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Unexpected server error */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    repositories: {
        parameters: {
            query?: {
                page?: number;
                /** @description Filters by repository name prefix, at most 100 characters. */
                q?: string;
                size?: number;
                sort?: string;
            };
            header?: never;
            path: {
                installationId: number;
                orgId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description One page of repositories */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponsePageResponsePickerRepositoryResponse"];
                };
            };
            /** @description q is longer than 100 characters */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Missing or invalid credentials */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description INSTALLATION_NOT_ACCESSIBLE, GITHUB_AUTHORIZATION_REQUIRED, NOT_A_MEMBER or INSUFFICIENT_ROLE */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description INSTALLATION_NOT_FOUND: not linked to this org. ORG_NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Conflict with current resource state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Rate limit exceeded */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Unexpected server error */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description EXTERNAL_SERVICE_ERROR: GitHub failed after retries, or its breaker is open. GITHUB_REQUEST_REJECTED or GITHUB_CREDENTIAL_REJECTED: GitHub refused the call */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
    receive: {
        parameters: {
            query?: never;
            header: {
                /** @description The delivery GUID; a delivery already stored is answered 200 and not reprocessed. */
                "X-GitHub-Delivery": string;
                /** @description The event name, such as push or installation. */
                "X-GitHub-Event": string;
                /** @description HMAC-SHA256 of the raw body under the app's webhook secret, hex encoded. */
                "X-Hub-Signature-256": string;
            };
            path?: never;
            cookie?: never;
        };
        /** @description GitHub's event payload, exactly as GitHub signed it. */
        requestBody: {
            content: {
                "application/json": Record<string, never>;
            };
        };
        responses: {
            /** @description A duplicate of a delivery already stored, or a ping */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseVoid"];
                };
            };
            /** @description Stored for processing, or stored and ignored for an event Pallet doesn't use */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseVoid"];
                };
            };
            /** @description INVALID_WEBHOOK_HEADERS, MALFORMED_WEBHOOK_PAYLOAD or WEBHOOK_BODY_UNREADABLE, all only after the signature passed */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description WEBHOOK_SIGNATURE_INVALID: the signature is missing or wrong */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description WEBHOOK_SOURCE_NOT_ALLOWED: outside GitHub's hook ranges, only when the IP allowlist is on */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Resource not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Conflict with current resource state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description PAYLOAD_TOO_LARGE: the body is over the size limit */
            413: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description UNSUPPORTED_MEDIA_TYPE: a signed body that isn't application/json */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Rate limit exceeded */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description Unexpected server error */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ErrorResponse"];
                };
            };
            /** @description SERVICE_UNAVAILABLE: the database is unreachable; GitHub retries */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ErrorResponse"];
                };
            };
        };
    };
}
