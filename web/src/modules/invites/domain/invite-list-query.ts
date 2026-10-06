import type { PageRequest } from "@/shared/domain/page";
import { can, type OrgAccessContext } from "@/shared/domain/permissions";
import { INVITE_STATUSES, type InviteStatus } from "./invite";

export const INVITE_STATUS_FILTERS = [...INVITE_STATUSES, "ALL"] as const;

export type InviteStatusFilter = (typeof INVITE_STATUS_FILTERS)[number];

export const INVITE_PAGE_SIZE = 20;

/** The backend's largest page. */
export const INVITE_LOOKUP_SIZE = 100;

/** The list as the address bar holds it: pages count from one. */
export interface InviteListParams {
    readonly inviteStatus: InviteStatusFilter;
    readonly invitePage: number;
}

export const DEFAULT_INVITE_LIST_PARAMS: InviteListParams = { inviteStatus: "PENDING", invitePage: 1 };

/** One page as it is asked of the backend; a null status asks for every invite. */
export interface InviteListQuery {
    readonly status: InviteStatus | null;
    readonly page: number;
    readonly size: number;
}

/**
 * Undefined for anyone who can't manage invites, including everyone in a personal organization, so a
 * caller below admin never sends a request that can only answer `403`.
 */
export function inviteListQuery(
    params: InviteListParams,
    viewer: OrgAccessContext | undefined,
): InviteListQuery | undefined {
    if (viewer === undefined || !can(viewer, "invites.manage")) return undefined;
    return {
        status: params.inviteStatus === "ALL" ? null : params.inviteStatus,
        page: Number.isInteger(params.invitePage) && params.invitePage > 1 ? params.invitePage - 1 : 0,
        size: INVITE_PAGE_SIZE,
    };
}

/** Newest first, which is also the backend's default. */
export function toPageRequest({ page, size }: InviteListQuery): PageRequest {
    return { page, size, sort: [{ field: "createdAt", direction: "desc" }] };
}

/** Every pending invite fits on one page: `max-pending-per-org` (50) caps them below the page size. */
export const ALL_PENDING_QUERY: InviteListQuery = { status: "PENDING", page: 0, size: INVITE_LOOKUP_SIZE };

export const RECENT_INVITES_QUERY: InviteListQuery = { status: null, page: 0, size: INVITE_LOOKUP_SIZE };
