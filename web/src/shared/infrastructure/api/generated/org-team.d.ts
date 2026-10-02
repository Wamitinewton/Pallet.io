export interface paths {
    "/invites/{token}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Preview an invite
         * @description Public. Shows what an emailed invite link is for, so the accept page can render before the invitee signs up. The token is verified before anything is read; the invitee's email is masked.
         */
        get: operations["preview"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/orgs": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List my organizations
         * @description Every organization the caller is an active member of, personal and team, with the caller's role in each. The personal organization comes first by default. Requires any signed-in account; no organization membership is involved, and the result is scoped to the caller alone.
         */
        get: operations["listMine"];
        put?: never;
        /**
         * Create a team organization
         * @description Creates a TEAM organization owned by the caller. The slug is derived from the name when omitted and must be unique across every organization. Requires any signed-in account; no organization membership is involved, and the result is scoped to the caller alone. Retrying conflicts (409) rather than creating a duplicate.
         */
        post: operations["create"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/orgs/{orgId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Get an organization
         * @description Returns the organization with live member, team and app counts. Requires any active member of the organization, judged from your current membership, not your token's roles. An organization other than the caller's own is reported as 404 ORG_NOT_FOUND.
         */
        get: operations["get"];
        put?: never;
        post?: never;
        /**
         * Delete an organization
         * @description Soft-deletes a TEAM organization: every member is removed, pending invites are revoked and apps are deleted. Requires the OWNER, judged from your current membership, not your token's roles. Also requires a recent sign-in; a stale session is rejected with 403 REAUTHENTICATION_REQUIRED. The X-Confirm-Slug header must equal the organization's slug.
         */
        delete: operations["delete"];
        options?: never;
        head?: never;
        /**
         * Rename an organization
         * @description Changes the display name only; the slug and owner never change here. Requires ADMIN or above, judged from your current membership, not your token's roles.
         */
        patch: operations["update"];
        trace?: never;
    };
    "/orgs/{orgId}/apps": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List apps
         * @description Optionally filtered by team or cloud provider. Requires any active member of the organization, judged from your current membership, not your token's roles.
         */
        get: operations["list_2"];
        put?: never;
        /**
         * Create an app
         * @description The cloud provider and region are chosen here and can never be changed. The region must be on the allow-list for the provider. Requires DEVELOPER or above, judged from your current membership, not your token's roles. Retrying conflicts (409) rather than creating a duplicate.
         */
        post: operations["create_3"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/orgs/{orgId}/apps/{appId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Get an app
         * @description Requires any active member of the organization, judged from your current membership, not your token's roles.
         */
        get: operations["get_3"];
        put?: never;
        post?: never;
        /**
         * Delete an app
         * @description Soft delete; the app's slug becomes reusable. Requires ADMIN or above, judged from your current membership, not your token's roles.
         */
        delete: operations["delete_2"];
        options?: never;
        head?: never;
        /**
         * Update an app
         * @description Changes the name and the owning team only; sending null for teamId detaches the app. Any other field, including the cloud provider and region, is rejected with 400. Requires DEVELOPER or above, judged from your current membership, not your token's roles.
         */
        patch: operations["update_1"];
        trace?: never;
    };
    "/orgs/{orgId}/invites": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List invites
         * @description An invite past its expiry reads as EXPIRED even before the background sweep marks it. Requires ADMIN or above, judged from your current membership, not your token's roles.
         */
        get: operations["list_1"];
        put?: never;
        /**
         * Invite someone to the organization
         * @description Emails the invitee a single-use link. The token is never returned. An OWNER may grant ADMIN, DEVELOPER or VIEWER; an ADMIN only DEVELOPER or VIEWER; nobody may grant OWNER. Requires ADMIN or above, judged from your current membership, not your token's roles. Retrying conflicts (409) rather than creating a duplicate.
         */
        post: operations["create_2"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/orgs/{orgId}/invites/{inviteId}": {
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
         * Revoke a pending invite
         * @description Revocation stops the invite showing as pending. A link already emailed still verifies cryptographically; if it is used, the resulting account is disabled shortly afterwards. Requires ADMIN or above, judged from your current membership, not your token's roles.
         */
        delete: operations["revoke"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/orgs/{orgId}/invites/{inviteId}/resend": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Resend a pending invite
         * @description Issues a fresh link with a new expiry for the same invite. Subject to a cooldown between sends and a maximum send count. Requires ADMIN or above, judged from your current membership, not your token's roles. Retrying conflicts (409) rather than creating a duplicate.
         */
        post: operations["resend"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/orgs/{orgId}/members": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List members
         * @description Active members by default. Filtering on status REMOVED requires ADMIN or above. Sortable by displayName, joinedAt and role; any other sort field is a 400 INVALID_SORT. Requires any active member of the organization, judged from your current membership, not your token's roles.
         */
        get: operations["list_3"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/orgs/{orgId}/members/{userId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Get a member
         * @description Requires any active member of the organization, judged from your current membership, not your token's roles.
         */
        get: operations["get_2"];
        put?: never;
        post?: never;
        /**
         * Remove a member or leave the organization
         * @description The owner may remove anyone but themselves; an ADMIN may remove DEVELOPER and VIEWER members; any member may remove themselves. The owner cannot be removed or leave: transfer ownership first. Removing an already-removed member is a 404. Requires any active member of the organization, judged from your current membership, not your token's roles. Further limits depend on the target's role.
         */
        delete: operations["remove"];
        options?: never;
        head?: never;
        /**
         * Change a member's role
         * @description Sets the role to ADMIN, DEVELOPER or VIEWER. The owner cannot be targeted and OWNER cannot be assigned here; use transfer-ownership. Setting the current role is a 200 with no change. Requires the OWNER, judged from your current membership, not your token's roles.
         */
        patch: operations["changeRole"];
        trace?: never;
    };
    "/orgs/{orgId}/members/{userId}/transfer-ownership": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Transfer ownership to another member
         * @description The target becomes OWNER and the caller becomes ADMIN in one transaction. The target must be an active member. Requires the OWNER, judged from your current membership, not your token's roles. Also requires a recent sign-in; a stale session is rejected with 403 REAUTHENTICATION_REQUIRED. A retry after success is rejected because the caller is no longer the owner; it does not transfer twice.
         */
        post: operations["transferOwnership"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/orgs/{orgId}/members/me": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Get the caller's own membership
         * @description Returns the caller's current role, so a client needs no token-claim parsing. Requires any active member of the organization, judged from your current membership, not your token's roles.
         */
        get: operations["me"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/orgs/{orgId}/teams": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List teams
         * @description Requires any active member of the organization, judged from your current membership, not your token's roles.
         */
        get: operations["list"];
        put?: never;
        /**
         * Create a team
         * @description The slug is derived from the name when omitted. Requires ADMIN or above, judged from your current membership, not your token's roles. Retrying conflicts (409) rather than creating a duplicate.
         */
        post: operations["create_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/orgs/{orgId}/teams/{teamId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Get a team
         * @description Requires any active member of the organization, judged from your current membership, not your token's roles.
         */
        get: operations["get_1"];
        put?: never;
        post?: never;
        /**
         * Delete a team
         * @description Detaches the team's apps and removes its member assignments; the members stay in the organization. Requires ADMIN or above, judged from your current membership, not your token's roles.
         */
        delete: operations["delete_1"];
        options?: never;
        head?: never;
        /**
         * Rename a team
         * @description Changes the name only. Requires ADMIN or above, judged from your current membership, not your token's roles.
         */
        patch: operations["rename"];
        trace?: never;
    };
    "/orgs/{orgId}/teams/{teamId}/members": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List a team's members
         * @description Requires any active member of the organization, judged from your current membership, not your token's roles.
         */
        get: operations["listMembers"];
        put?: never;
        /**
         * Add a member to a team
         * @description Assigns an existing active organization member; it does not invite anyone. Requires ADMIN or above, judged from your current membership, not your token's roles. Retrying conflicts (409) rather than creating a duplicate.
         */
        post: operations["addMember"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/orgs/{orgId}/teams/{teamId}/members/{userId}": {
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
         * Remove a member from a team
         * @description Requires ADMIN or above, judged from your current membership, not your token's roles.
         */
        delete: operations["removeMember"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        AddTeamMemberRequest: {
            /**
             * @description Id of an active member of the organization
             * @example 4d1c9a52-7f3e-4a86-9c0b-2e5f8a1b3c47
             */
            userId: string;
        };
        ApiResponseAppDto: {
            data?: components["schemas"]["AppDto"];
            message?: string;
            success?: boolean;
        };
        ApiResponseInviteDto: {
            data?: components["schemas"]["InviteDto"];
            message?: string;
            success?: boolean;
        };
        ApiResponseInvitePreviewDto: {
            data?: components["schemas"]["InvitePreviewDto"];
            message?: string;
            success?: boolean;
        };
        ApiResponseMemberDto: {
            data?: components["schemas"]["MemberDto"];
            message?: string;
            success?: boolean;
        };
        ApiResponseOrgDto: {
            data?: components["schemas"]["OrgDto"];
            message?: string;
            success?: boolean;
        };
        ApiResponsePageResponseAppDto: {
            data?: components["schemas"]["PageResponseAppDto"];
            message?: string;
            success?: boolean;
        };
        ApiResponsePageResponseInviteDto: {
            data?: components["schemas"]["PageResponseInviteDto"];
            message?: string;
            success?: boolean;
        };
        ApiResponsePageResponseMemberDto: {
            data?: components["schemas"]["PageResponseMemberDto"];
            message?: string;
            success?: boolean;
        };
        ApiResponsePageResponseOrgSummaryDto: {
            data?: components["schemas"]["PageResponseOrgSummaryDto"];
            message?: string;
            success?: boolean;
        };
        ApiResponsePageResponseTeamDto: {
            data?: components["schemas"]["PageResponseTeamDto"];
            message?: string;
            success?: boolean;
        };
        ApiResponseTeamDto: {
            data?: components["schemas"]["TeamDto"];
            message?: string;
            success?: boolean;
        };
        AppDto: {
            /** @enum {string} */
            cloudProvider?: "AWS" | "GCP";
            /** Format: date-time */
            createdAt?: string;
            /** Format: uuid */
            id?: string;
            name?: string;
            region?: string;
            slug?: string;
            /** Format: uuid */
            teamId?: string;
            /** Format: date-time */
            updatedAt?: string;
        };
        ChangeRoleRequest: {
            /**
             * @description New role; OWNER is assigned only by transferring ownership
             * @example ADMIN
             * @enum {string}
             */
            role: "ADMIN" | "DEVELOPER" | "VIEWER";
        };
        Counts: {
            /** Format: int64 */
            apps?: number;
            /** Format: int64 */
            members?: number;
            /** Format: int64 */
            teams?: number;
        };
        CreateAppRequest: {
            /**
             * @description Where the app runs. Fixed once the app is created.
             * @example AWS
             * @enum {string}
             */
            cloudProvider: "AWS" | "GCP";
            /**
             * @description Display name
             * @example Storefront API
             */
            name: string;
            /**
             * @description A region on the provider's allow-list. Fixed once the app is created.
             * @example eu-west-1
             */
            region: string;
            /**
             * @description DNS-1123 label; derived from the name when omitted
             * @example storefront-api
             */
            slug?: string;
            /**
             * Format: uuid
             * @description Team that owns the app; optional
             */
            teamId?: string;
        };
        CreateInviteRequest: {
            /**
             * Format: email
             * @description Address the invite link is sent to
             * @example dev@example.com
             */
            email: string;
            /**
             * @description Role granted on acceptance; OWNER cannot be invited
             * @example DEVELOPER
             * @enum {string}
             */
            role: "ADMIN" | "DEVELOPER" | "VIEWER";
        };
        CreateOrgRequest: {
            /**
             * @description Display name
             * @example Acme Inc
             */
            name: string;
            /**
             * @description DNS-1123 label, unique across every organization; derived from the name when omitted
             * @example acme-inc
             */
            slug?: string;
        };
        CreateTeamRequest: {
            /**
             * @description Display name
             * @example Platform
             */
            name: string;
            /**
             * @description DNS-1123 label; derived from the name when omitted
             * @example platform
             */
            slug?: string;
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
        /** @description A pending or past invite. The signed token and the accept link are never part of any response. */
        InviteDto: {
            /** Format: date-time */
            createdAt?: string;
            email?: string;
            /** Format: date-time */
            expiresAt?: string;
            /** Format: uuid */
            id?: string;
            invitedByUserId?: string;
            /** @enum {string} */
            role?: "VIEWER" | "DEVELOPER" | "ADMIN" | "OWNER";
            /** Format: int32 */
            sendCount?: number;
            /** @enum {string} */
            status?: "PENDING" | "ACCEPTED" | "REVOKED" | "EXPIRED";
        };
        InvitePreviewDto: {
            /** Format: date-time */
            expiresAt?: string;
            inviterName?: string;
            maskedEmail?: string;
            orgName?: string;
            /** @enum {string} */
            role?: "VIEWER" | "DEVELOPER" | "ADMIN" | "OWNER";
        };
        MemberDto: {
            displayName?: string;
            email?: string;
            /** Format: date-time */
            joinedAt?: string;
            /** @enum {string} */
            role?: "VIEWER" | "DEVELOPER" | "ADMIN" | "OWNER";
            /** @enum {string} */
            status?: "ACTIVE" | "REMOVED";
            userId?: string;
        };
        OrgDto: {
            counts?: components["schemas"]["Counts"];
            /** Format: date-time */
            createdAt?: string;
            /** @enum {string} */
            kind?: "PERSONAL" | "TEAM";
            name?: string;
            orgId?: string;
            ownerUserId?: string;
            slug?: string;
            /** @enum {string} */
            status?: "ACTIVE" | "DELETED";
        };
        OrgSummaryDto: {
            /** @enum {string} */
            kind?: "PERSONAL" | "TEAM";
            /** @enum {string} */
            myRole?: "VIEWER" | "DEVELOPER" | "ADMIN" | "OWNER";
            name?: string;
            orgId?: string;
            slug?: string;
        };
        PageResponseAppDto: {
            content?: components["schemas"]["AppDto"][];
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
        PageResponseInviteDto: {
            content?: components["schemas"]["InviteDto"][];
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
        PageResponseMemberDto: {
            content?: components["schemas"]["MemberDto"][];
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
        PageResponseOrgSummaryDto: {
            content?: components["schemas"]["OrgSummaryDto"][];
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
        PageResponseTeamDto: {
            content?: components["schemas"]["TeamDto"][];
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
        TeamDto: {
            /** Format: date-time */
            createdAt?: string;
            /** Format: uuid */
            id?: string;
            /** Format: int64 */
            memberCount?: number;
            name?: string;
            slug?: string;
            /** Format: date-time */
            updatedAt?: string;
        };
        UpdateAppRequest: {
            name?: string;
            /** Format: uuid */
            teamId?: string;
        };
        UpdateOrgRequest: {
            /**
             * @description New display name
             * @example Acme Corp
             */
            name: string;
        };
        UpdateTeamRequest: {
            /**
             * @description New display name
             * @example Platform
             */
            name: string;
        };
        ValidationError: {
            field?: string;
            message?: string;
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
    preview: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Signed invite token from the invite email */
                token: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description What the invite is for */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseInvitePreviewDto"];
                };
            };
            /** @description INVALID_TOKEN: bad signature, expired or wrong purpose */
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
            /** @description INVITE_NO_LONGER_VALID: revoked, accepted or expired */
            410: {
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
        };
    };
    listMine: {
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
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponsePageResponseOrgSummaryDto"];
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
    create: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateOrgRequest"];
            };
        };
        responses: {
            /** @description Organization created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseOrgDto"];
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
            /** @description SLUG_TAKEN */
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
        };
    };
    get: {
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
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseOrgDto"];
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
    delete: {
        parameters: {
            query?: never;
            header: {
                /**
                 * @description The organization's slug, repeated to confirm the deletion
                 * @example acme-corp
                 */
                "X-Confirm-Slug": string;
            };
            path: {
                orgId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Deleted */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description CONFIRMATION_MISMATCH: X-Confirm-Slug is missing or wrong */
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
            /** @description INSUFFICIENT_ROLE, NOT_A_MEMBER or REAUTHENTICATION_REQUIRED */
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
            /** @description PERSONAL_ORG_IMMUTABLE: a personal organization is never deleted here */
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
        };
    };
    update: {
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
                "application/json": components["schemas"]["UpdateOrgRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseOrgDto"];
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
    list_2: {
        parameters: {
            query?: {
                cloudProvider?: "AWS" | "GCP";
                page?: number;
                size?: number;
                sort?: string;
                teamId?: string;
            };
            header?: never;
            path: {
                orgId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponsePageResponseAppDto"];
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
    create_3: {
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
                "application/json": components["schemas"]["CreateAppRequest"];
            };
        };
        responses: {
            /** @description App created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseAppDto"];
                };
            };
            /** @description INVALID_REGION or a validation failure */
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
            /** @description SLUG_TAKEN or QUOTA_EXCEEDED */
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
        };
    };
    get_3: {
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
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseAppDto"];
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
    delete_2: {
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
            /** @description Deleted */
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
    update_1: {
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
                "application/json": components["schemas"]["UpdateAppRequest"];
            };
        };
        responses: {
            /** @description App updated */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseAppDto"];
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
            /** @description CONCURRENT_MODIFICATION */
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
        };
    };
    list_1: {
        parameters: {
            query?: {
                page?: number;
                size?: number;
                sort?: string;
                status?: "PENDING" | "ACCEPTED" | "REVOKED" | "EXPIRED";
            };
            header?: never;
            path: {
                orgId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponsePageResponseInviteDto"];
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
    create_2: {
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
                "application/json": components["schemas"]["CreateInviteRequest"];
            };
        };
        responses: {
            /** @description Invite created and emailed */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseInviteDto"];
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
            /** @description ALREADY_A_MEMBER, MEMBER_PREVIOUSLY_REMOVED, INVITE_ALREADY_PENDING, QUOTA_EXCEEDED or PERSONAL_ORG_IMMUTABLE */
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
        };
    };
    revoke: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                inviteId: string;
                orgId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Revoked */
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
            /** @description INVITE_NOT_PENDING */
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
        };
    };
    resend: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                inviteId: string;
                orgId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Invite resent */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseInviteDto"];
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
            /** @description INVITE_NOT_PENDING or QUOTA_EXCEEDED */
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
        };
    };
    list_3: {
        parameters: {
            query?: {
                page?: number;
                q?: string;
                role?: "VIEWER" | "DEVELOPER" | "ADMIN" | "OWNER";
                size?: number;
                sort?: string;
                status?: "ACTIVE" | "REMOVED";
            };
            header?: never;
            path: {
                orgId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponsePageResponseMemberDto"];
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
    get_2: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
                userId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseMemberDto"];
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
    remove: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
                userId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Removed */
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
            /** @description LAST_OWNER or CONCURRENT_MODIFICATION */
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
        };
    };
    changeRole: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
                userId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ChangeRoleRequest"];
            };
        };
        responses: {
            /** @description Role changed */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseMemberDto"];
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
            /** @description INVALID_ROLE_TRANSITION, LAST_OWNER or CONCURRENT_MODIFICATION */
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
        };
    };
    transferOwnership: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
                userId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Ownership transferred */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseMemberDto"];
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
            /** @description INSUFFICIENT_ROLE, NOT_A_MEMBER or REAUTHENTICATION_REQUIRED */
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
            /** @description INVALID_ROLE_TRANSITION or CONCURRENT_MODIFICATION */
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
        };
    };
    me: {
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
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseMemberDto"];
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
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponsePageResponseTeamDto"];
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
    create_1: {
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
                "application/json": components["schemas"]["CreateTeamRequest"];
            };
        };
        responses: {
            /** @description Team created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseTeamDto"];
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
            /** @description SLUG_TAKEN or QUOTA_EXCEEDED */
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
        };
    };
    get_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
                teamId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseTeamDto"];
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
    delete_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
                teamId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Deleted */
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
    rename: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
                teamId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateTeamRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseTeamDto"];
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
    listMembers: {
        parameters: {
            query?: {
                page?: number;
                size?: number;
                sort?: string;
            };
            header?: never;
            path: {
                orgId: string;
                teamId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponsePageResponseMemberDto"];
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
    addMember: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
                teamId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AddTeamMemberRequest"];
            };
        };
        responses: {
            /** @description Member added to the team */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ApiResponseMemberDto"];
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
            /** @description ALREADY_IN_TEAM */
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
        };
    };
    removeMember: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
                teamId: string;
                userId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Removed */
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
}
