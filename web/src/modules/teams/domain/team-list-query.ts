import type { PageRequest, SortDirection } from "@/shared/domain/page";

/** The backend's whitelist: any other field is a `400 INVALID_SORT`. */
export const TEAM_SORT_FIELDS = ["name", "createdAt"] as const;

export type TeamSortField = (typeof TEAM_SORT_FIELDS)[number];

export interface TeamSort {
    readonly field: TeamSortField;
    readonly direction: SortDirection;
}

export const DEFAULT_TEAM_SORT: TeamSort = { field: "name", direction: "asc" };

/** Divides evenly into the grid's three, two and one columns. */
export const TEAM_PAGE_SIZE = 24;

/** The list as the address bar holds it: pages count from one, and either field may be stale. */
export interface TeamListParams {
    readonly sort: TeamSort;
    readonly page: number;
}

export const DEFAULT_TEAM_LIST_PARAMS: TeamListParams = { sort: DEFAULT_TEAM_SORT, page: 1 };

/** One page as it is asked of the backend. */
export interface TeamListQuery {
    readonly sort: TeamSort;
    readonly page: number;
    readonly size: number;
}

function isSortField(value: string): value is TeamSortField {
    return (TEAM_SORT_FIELDS as readonly string[]).includes(value);
}

/** `field,direction` from the URL; anything off the whitelist reads as no sort at all. */
export function parseTeamSort(value: string): TeamSort | undefined {
    const [field = "", direction, ...rest] = value.split(",");
    if (rest.length > 0 || !isSortField(field)) return undefined;
    if (direction !== "asc" && direction !== "desc") return undefined;
    return { field, direction };
}

export function formatTeamSort({ field, direction }: TeamSort): string {
    return `${field},${direction}`;
}

export function sameTeamSort(a: TeamSort, b: TeamSort): boolean {
    return a.field === b.field && a.direction === b.direction;
}

function zeroBased(page: number): number {
    return Number.isInteger(page) && page > 1 ? page - 1 : 0;
}

export function teamListQuery({ sort, page }: TeamListParams): TeamListQuery {
    return { sort, page: zeroBased(page), size: TEAM_PAGE_SIZE };
}

export function toPageRequest({ page, size, sort }: TeamListQuery): PageRequest {
    return { page, size, sort: [sort] };
}

export const TEAM_MEMBER_PAGE_SIZE = 20;

/** The backend's largest page. */
export const TEAM_ROSTER_SIZE = 100;

/** One page of a team's people, always by name: the backend's default order. */
export interface TeamMemberListQuery {
    readonly page: number;
    readonly size: number;
}

export function teamMemberListQuery(page: number): TeamMemberListQuery {
    return { page: zeroBased(page), size: TEAM_MEMBER_PAGE_SIZE };
}

/**
 * Everyone on the team in one request, so a picker can leave them out. A team larger than this is caught
 * by the backend's `ALREADY_IN_TEAM` instead.
 */
export const TEAM_ROSTER_QUERY: TeamMemberListQuery = { page: 0, size: TEAM_ROSTER_SIZE };

/** The backend's largest page, which is also the most teams an organization may have. */
export const TEAM_DIRECTORY_SIZE = 100;

/** Every team, by name, in one request: enough for a filter or a select to offer them all. */
export const TEAM_DIRECTORY_QUERY: TeamListQuery = { sort: DEFAULT_TEAM_SORT, page: 0, size: TEAM_DIRECTORY_SIZE };
