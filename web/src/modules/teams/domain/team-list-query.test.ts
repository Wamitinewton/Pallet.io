import { describe, expect, it } from "vitest";
import {
    DEFAULT_TEAM_SORT,
    formatTeamSort,
    parseTeamSort,
    TEAM_DIRECTORY_QUERY,
    TEAM_MEMBER_PAGE_SIZE,
    TEAM_PAGE_SIZE,
    teamListQuery,
    teamMemberListQuery,
    toPageRequest,
    type TeamSort,
} from "./team-list-query";

describe("parseTeamSort", () => {
    it.each<[string, TeamSort]>([
        ["name,asc", { field: "name", direction: "asc" }],
        ["createdAt,desc", { field: "createdAt", direction: "desc" }],
    ])("reads %s", (value, sort) => {
        expect(parseTeamSort(value)).toEqual(sort);
        expect(formatTeamSort(sort)).toBe(value);
    });

    it.each(["memberCount,desc", "name", "name,up", "name,asc,extra", ""])("refuses %j", (value) => {
        expect(parseTeamSort(value)).toBeUndefined();
    });
});

describe("teamListQuery", () => {
    it("asks for a full grid page, counting pages from zero", () => {
        expect(teamListQuery({ sort: DEFAULT_TEAM_SORT, page: 3 })).toEqual({
            sort: DEFAULT_TEAM_SORT,
            page: 2,
            size: TEAM_PAGE_SIZE,
        });
    });

    it.each([0, -2, 1.5, Number.NaN])("reads page %s from the URL as the first", (page) => {
        expect(teamListQuery({ sort: DEFAULT_TEAM_SORT, page }).page).toBe(0);
    });

    it("sends the sort the URL chose", () => {
        const query = teamListQuery({ sort: { field: "createdAt", direction: "desc" }, page: 1 });
        expect(toPageRequest(query)).toEqual({
            page: 0,
            size: TEAM_PAGE_SIZE,
            sort: [{ field: "createdAt", direction: "desc" }],
        });
    });
});

describe("teamMemberListQuery", () => {
    it("asks for one page of people", () => {
        expect(teamMemberListQuery(2)).toEqual({ page: 1, size: TEAM_MEMBER_PAGE_SIZE });
        expect(teamMemberListQuery(-1)).toEqual({ page: 0, size: TEAM_MEMBER_PAGE_SIZE });
    });
});

describe("TEAM_DIRECTORY_QUERY", () => {
    it("asks for every team, by name, in the backend's largest page", () => {
        expect(toPageRequest(TEAM_DIRECTORY_QUERY)).toEqual({
            page: 0,
            size: 100,
            sort: [{ field: "name", direction: "asc" }],
        });
    });
});
