import type { PageRequest, SortDirection } from "@/shared/domain/page";
import { can, type OrgAccessContext } from "@/shared/domain/permissions";
import type { Role } from "@/shared/domain/role";
import type { MemberStatus } from "./member";

/** The backend's whitelist: any other field is a `400 INVALID_SORT`. */
export const MEMBER_SORT_FIELDS = ["displayName", "joinedAt", "role"] as const;

export type MemberSortField = (typeof MEMBER_SORT_FIELDS)[number];

export interface MemberSort {
    readonly field: MemberSortField;
    readonly direction: SortDirection;
}

export const DEFAULT_MEMBER_SORT: MemberSort = { field: "displayName", direction: "asc" };
export const MEMBER_PAGE_SIZES = [10, 20, 50, 100] as const;
export const DEFAULT_MEMBER_PAGE_SIZE = 20;
export const MAX_MEMBER_SEARCH_LENGTH = 100;

/** The list as the address bar holds it: pages count from one, and any field may be missing or stale. */
export interface MemberListParams {
    readonly q: string;
    readonly role: Role | null;
    readonly status: MemberStatus;
    readonly sort: MemberSort;
    readonly page: number;
    readonly size: number;
}

export const DEFAULT_MEMBER_LIST_PARAMS: MemberListParams = {
    q: "",
    role: null,
    status: "ACTIVE",
    sort: DEFAULT_MEMBER_SORT,
    page: 1,
    size: DEFAULT_MEMBER_PAGE_SIZE,
};

/** One page as it is asked of the backend. */
export interface MemberListQuery {
    readonly q: string;
    readonly role: Role | null;
    readonly status: MemberStatus;
    readonly sort: MemberSort;
    readonly page: number;
    readonly size: number;
}

export type MemberFilterChange = Partial<Pick<MemberListParams, "q" | "role" | "status" | "sort" | "size">>;

function isSortField(value: string): value is MemberSortField {
    return (MEMBER_SORT_FIELDS as readonly string[]).includes(value);
}

/** `field,direction` from the URL; anything off the whitelist reads as no sort at all. */
export function parseMemberSort(value: string): MemberSort | undefined {
    const [field = "", direction, ...rest] = value.split(",");
    if (rest.length > 0 || !isSortField(field)) return undefined;
    if (direction !== "asc" && direction !== "desc") return undefined;
    return { field, direction };
}

export function formatMemberSort({ field, direction }: MemberSort): string {
    return `${field},${direction}`;
}

export function sameMemberSort(a: MemberSort, b: MemberSort): boolean {
    return a.field === b.field && a.direction === b.direction;
}

export function normalizeMemberSearch(q: string): string {
    return q.trim().slice(0, MAX_MEMBER_SEARCH_LENGTH);
}

function pageSize(size: number): number {
    return (MEMBER_PAGE_SIZES as readonly number[]).includes(size) ? size : DEFAULT_MEMBER_PAGE_SIZE;
}

/**
 * Removed members are asked for only by someone allowed to see them, so a shared link can't make a
 * developer's page fail with `403`.
 */
export function memberListQuery(params: MemberListParams, viewer: OrgAccessContext | undefined): MemberListQuery {
    const canViewRemoved = viewer !== undefined && can(viewer, "members.viewRemoved");
    return {
        q: normalizeMemberSearch(params.q),
        role: params.role,
        status: canViewRemoved ? params.status : "ACTIVE",
        sort: params.sort,
        page: Number.isInteger(params.page) && params.page > 1 ? params.page - 1 : 0,
        size: pageSize(params.size),
    };
}

/** Any change to what the list shows starts it again from the first page. */
export function withFilters(params: MemberListParams, change: MemberFilterChange): MemberListParams {
    return { ...params, ...change, page: DEFAULT_MEMBER_LIST_PARAMS.page };
}

export function isFiltered({ q, role, status }: MemberListQuery): boolean {
    return q !== "" || role !== null || status !== "ACTIVE";
}

export function toPageRequest({ page, size, sort }: MemberListQuery): PageRequest {
    return { page, size, sort: [sort] };
}

export const MEMBER_PICKER_SIZE = 10;

/** A picker offers active members only, by name, a short page at a time. */
export function memberPickerQuery(q: string): MemberListQuery {
    return {
        ...DEFAULT_MEMBER_LIST_PARAMS,
        q: normalizeMemberSearch(q),
        status: "ACTIVE",
        page: 0,
        size: MEMBER_PICKER_SIZE,
    };
}

/** The backend's largest page. */
export const MEMBER_DIRECTORY_SIZE = 100;

/**
 * Active members, owners and admins first, in one request: enough to put a name to whoever acted, such as
 * an inviter, without a request per row.
 */
export const MEMBER_DIRECTORY_QUERY: MemberListQuery = {
    ...DEFAULT_MEMBER_LIST_PARAMS,
    sort: { field: "role", direction: "desc" },
    page: 0,
    size: MEMBER_DIRECTORY_SIZE,
};
