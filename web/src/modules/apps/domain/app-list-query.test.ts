import { describe, expect, it } from "vitest";
import {
    APP_PAGE_SIZE,
    appListQuery,
    CLEARED_APP_FILTERS,
    DEFAULT_APP_LIST_PARAMS,
    DEFAULT_APP_SORT,
    formatAppSort,
    isFiltered,
    MAX_APP_SEARCH_LENGTH,
    NO_TEAM_PARAM,
    parseAppSort,
    teamAppsQuery,
    toPageRequest,
    withFilters,
    type AppSort,
} from "./app-list-query";
import { PAYMENTS_TEAM_ID } from "./testing/fixtures";

describe("parseAppSort", () => {
    it.each<[string, AppSort]>([
        ["name,asc", { field: "name", direction: "asc" }],
        ["createdAt,desc", { field: "createdAt", direction: "desc" }],
    ])("reads %s", (value, sort) => {
        expect(parseAppSort(value)).toEqual(sort);
        expect(formatAppSort(sort)).toBe(value);
    });

    it.each(["updatedAt,desc", "region,asc", "name", "name,up", "name,asc,extra", ""])("refuses %j", (value) => {
        expect(parseAppSort(value)).toBeUndefined();
    });
});

describe("appListQuery", () => {
    it("asks for the newest apps first by default, from the first page", () => {
        expect(appListQuery(DEFAULT_APP_LIST_PARAMS)).toEqual({
            q: "",
            team: { kind: "any" },
            cloudProvider: null,
            sort: DEFAULT_APP_SORT,
            page: 0,
            size: APP_PAGE_SIZE,
        });
    });

    it("carries the search, team and provider the URL chose, counting pages from zero", () => {
        expect(
            appListQuery({ q: "  api ", team: PAYMENTS_TEAM_ID, cloud: "GCP", sort: DEFAULT_APP_SORT, page: 3 }),
        ).toMatchObject({
            q: "api",
            team: { kind: "team", teamId: PAYMENTS_TEAM_ID },
            cloudProvider: "GCP",
            page: 2,
        });
    });

    it("cuts a search the URL made too long to the backend's limit", () => {
        expect(appListQuery({ ...DEFAULT_APP_LIST_PARAMS, q: "a".repeat(150) }).q).toHaveLength(MAX_APP_SEARCH_LENGTH);
    });

    it("reads the no-team value as apps without a team", () => {
        expect(appListQuery({ ...DEFAULT_APP_LIST_PARAMS, team: NO_TEAM_PARAM }).team).toEqual({ kind: "unassigned" });
    });

    it.each(["payments", "3f2b8c1e", "None", ""])("ignores a team %j that can't be an id", (team) => {
        expect(appListQuery({ ...DEFAULT_APP_LIST_PARAMS, team }).team).toEqual({ kind: "any" });
    });

    it.each([0, -2, 1.5, Number.NaN])("reads page %s from the URL as the first", (page) => {
        expect(appListQuery({ ...DEFAULT_APP_LIST_PARAMS, page }).page).toBe(0);
    });

    it("sends the sort the URL chose", () => {
        const query = appListQuery({ ...DEFAULT_APP_LIST_PARAMS, sort: { field: "name", direction: "asc" }, page: 2 });

        expect(toPageRequest(query)).toEqual({
            page: 1,
            size: APP_PAGE_SIZE,
            sort: [{ field: "name", direction: "asc" }],
        });
    });
});

describe("withFilters", () => {
    it("clears the search, team and provider together, keeping the sort", () => {
        const sort: AppSort = { field: "name", direction: "asc" };

        expect(
            withFilters({ q: "api", team: NO_TEAM_PARAM, cloud: "GCP", sort, page: 3 }, CLEARED_APP_FILTERS),
        ).toEqual({ ...DEFAULT_APP_LIST_PARAMS, sort });
    });

    it("goes back to the first page on any change to what the list shows", () => {
        expect(withFilters({ ...DEFAULT_APP_LIST_PARAMS, page: 4 }, { cloud: "AWS" })).toEqual({
            ...DEFAULT_APP_LIST_PARAMS,
            cloud: "AWS",
            page: 1,
        });
    });
});

describe("isFiltered", () => {
    it("is true only when a search, a team, no team or a provider narrows the list", () => {
        expect(isFiltered(appListQuery(DEFAULT_APP_LIST_PARAMS))).toBe(false);
        expect(isFiltered(appListQuery({ ...DEFAULT_APP_LIST_PARAMS, q: "   " }))).toBe(false);
        expect(isFiltered(appListQuery({ ...DEFAULT_APP_LIST_PARAMS, q: "api" }))).toBe(true);
        expect(isFiltered(appListQuery({ ...DEFAULT_APP_LIST_PARAMS, cloud: "AWS" }))).toBe(true);
        expect(isFiltered(appListQuery({ ...DEFAULT_APP_LIST_PARAMS, team: PAYMENTS_TEAM_ID }))).toBe(true);
        expect(isFiltered(appListQuery({ ...DEFAULT_APP_LIST_PARAMS, team: NO_TEAM_PARAM }))).toBe(true);
        expect(
            isFiltered(appListQuery({ ...DEFAULT_APP_LIST_PARAMS, sort: { field: "name", direction: "asc" } })),
        ).toBe(false);
    });
});

describe("teamAppsQuery", () => {
    it("asks for the first few of one team's apps by name", () => {
        expect(teamAppsQuery(PAYMENTS_TEAM_ID)).toEqual({
            q: "",
            team: { kind: "team", teamId: PAYMENTS_TEAM_ID },
            cloudProvider: null,
            sort: { field: "name", direction: "asc" },
            page: 0,
            size: 10,
        });
    });
});
