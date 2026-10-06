import { asTeamId, isUuid, type TeamId } from "@/shared/domain/ids";
import type { PageRequest, SortDirection } from "@/shared/domain/page";
import type { CloudProvider } from "./region-catalog";

/** The backend's whitelist: any other field is a `400 INVALID_SORT`. */
export const APP_SORT_FIELDS = ["name", "createdAt"] as const;

export type AppSortField = (typeof APP_SORT_FIELDS)[number];

export interface AppSort {
    readonly field: AppSortField;
    readonly direction: SortDirection;
}

/** The backend's own default order. */
export const DEFAULT_APP_SORT: AppSort = { field: "createdAt", direction: "desc" };

export const APP_PAGE_SIZE = 20;
export const MAX_APP_SEARCH_LENGTH = 100;

/** What the `team` URL parameter holds to ask for apps that belong to no team. */
export const NO_TEAM_PARAM = "none";

/** The backend refuses a team id and "no team" together, so the list asks for one or the other, never both. */
export type AppTeamFilter =
    { readonly kind: "any" } | { readonly kind: "unassigned" } | { readonly kind: "team"; readonly teamId: TeamId };

const ANY_TEAM: AppTeamFilter = { kind: "any" };

/** The list as the address bar holds it: pages count from one, and any field may be missing or stale. */
export interface AppListParams {
    readonly q: string;
    readonly team: string | null;
    readonly cloud: CloudProvider | null;
    readonly sort: AppSort;
    readonly page: number;
}

export const DEFAULT_APP_LIST_PARAMS: AppListParams = {
    q: "",
    team: null,
    cloud: null,
    sort: DEFAULT_APP_SORT,
    page: 1,
};

export type AppFilterChange = Partial<Pick<AppListParams, "q" | "team" | "cloud" | "sort">>;

export const CLEARED_APP_FILTERS: AppFilterChange = { q: "", team: null, cloud: null };

/** One page as it is asked of the backend. */
export interface AppListQuery {
    readonly q: string;
    readonly team: AppTeamFilter;
    readonly cloudProvider: CloudProvider | null;
    readonly sort: AppSort;
    readonly page: number;
    readonly size: number;
}

function isSortField(value: string): value is AppSortField {
    return (APP_SORT_FIELDS as readonly string[]).includes(value);
}

/** `field,direction` from the URL; anything off the whitelist reads as no sort at all. */
export function parseAppSort(value: string): AppSort | undefined {
    const [field = "", direction, ...rest] = value.split(",");
    if (rest.length > 0 || !isSortField(field)) return undefined;
    if (direction !== "asc" && direction !== "desc") return undefined;
    return { field, direction };
}

export function formatAppSort({ field, direction }: AppSort): string {
    return `${field},${direction}`;
}

export function sameAppSort(a: AppSort, b: AppSort): boolean {
    return a.field === b.field && a.direction === b.direction;
}

function zeroBased(page: number): number {
    return Number.isInteger(page) && page > 1 ? page - 1 : 0;
}

export function normalizeAppSearch(q: string): string {
    return q.trim().slice(0, MAX_APP_SEARCH_LENGTH);
}

/** A team the URL names that can't be an id filters nothing, rather than failing the list with `400`. */
export function parseAppTeamFilter(team: string | null): AppTeamFilter {
    if (team === NO_TEAM_PARAM) return { kind: "unassigned" };
    if (team !== null && isUuid(team)) return { kind: "team", teamId: asTeamId(team) };
    return ANY_TEAM;
}

export function appListQuery({ q, team, cloud, sort, page }: AppListParams): AppListQuery {
    return {
        q: normalizeAppSearch(q),
        team: parseAppTeamFilter(team),
        cloudProvider: cloud,
        sort,
        page: zeroBased(page),
        size: APP_PAGE_SIZE,
    };
}

/** Any change to what the list shows starts it again from the first page. */
export function withFilters(params: AppListParams, change: AppFilterChange): AppListParams {
    return { ...params, ...change, page: DEFAULT_APP_LIST_PARAMS.page };
}

export function isFiltered({ q, team, cloudProvider }: AppListQuery): boolean {
    return q !== "" || team.kind !== "any" || cloudProvider !== null;
}

export function toPageRequest({ page, size, sort }: AppListQuery): PageRequest {
    return { page, size, sort: [sort] };
}

export const TEAM_APPS_SIZE = 10;

/** The first few of a team's apps, by name, for the team's page. */
export function teamAppsQuery(teamId: TeamId): AppListQuery {
    return {
        q: "",
        team: { kind: "team", teamId },
        cloudProvider: null,
        sort: { field: "name", direction: "asc" },
        page: 0,
        size: TEAM_APPS_SIZE,
    };
}
