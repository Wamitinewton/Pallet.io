import type { OrgAccessContext } from "@/shared/domain/permissions";
import { describe, expect, it } from "vitest";
import {
    DEFAULT_MEMBER_LIST_PARAMS,
    DEFAULT_MEMBER_SORT,
    memberListQuery,
    memberPickerQuery,
    parseMemberSort,
    withFilters,
    type MemberListParams,
} from "./member-list-query";

const admin: OrgAccessContext = { role: "ADMIN", orgKind: "TEAM" };
const developer: OrgAccessContext = { role: "DEVELOPER", orgKind: "TEAM" };

const params = (overrides: Partial<MemberListParams> = {}): MemberListParams => ({
    ...DEFAULT_MEMBER_LIST_PARAMS,
    ...overrides,
});

describe("memberListQuery", () => {
    it("asks for the first twenty active members by name by default", () => {
        expect(memberListQuery(DEFAULT_MEMBER_LIST_PARAMS, admin)).toEqual({
            q: "",
            role: null,
            status: "ACTIVE",
            sort: { field: "displayName", direction: "asc" },
            page: 0,
            size: 20,
        });
    });

    it("counts pages from zero for the backend, and from one in the URL", () => {
        expect(memberListQuery(params({ page: 3 }), admin).page).toBe(2);
    });

    it.each([0, -2, 1.5, Number.NaN])("reads page %s as the first page", (page) => {
        expect(memberListQuery(params({ page }), admin).page).toBe(0);
    });

    it("falls back to the default size for one the page doesn't offer", () => {
        expect(memberListQuery(params({ size: 1000 }), admin).size).toBe(20);
        expect(memberListQuery(params({ size: 50 }), admin).size).toBe(50);
    });

    it("trims the search and holds it to the backend's 100 characters", () => {
        expect(memberListQuery(params({ q: "  ama  " }), admin).q).toBe("ama");
        expect(memberListQuery(params({ q: "a".repeat(140) }), admin).q).toHaveLength(100);
    });

    it("asks for removed members only for someone allowed to see them", () => {
        expect(memberListQuery(params({ status: "REMOVED" }), admin).status).toBe("REMOVED");
        expect(memberListQuery(params({ status: "REMOVED" }), developer).status).toBe("ACTIVE");
        expect(memberListQuery(params({ status: "REMOVED" }), undefined).status).toBe("ACTIVE");
    });
});

describe("parseMemberSort", () => {
    it.each([
        ["displayName,asc", { field: "displayName", direction: "asc" }],
        ["joinedAt,desc", { field: "joinedAt", direction: "desc" }],
        ["role,desc", { field: "role", direction: "desc" }],
    ] as const)("reads %s", (value, sort) => {
        expect(parseMemberSort(value)).toEqual(sort);
    });

    it.each(["email,asc", "displayName", "displayName,up", "joinedAt,asc,role", "", "roleRank,asc"])(
        "drops %j, which the backend would refuse",
        (value) => {
            expect(parseMemberSort(value)).toBeUndefined();
        },
    );

    it("leaves the default in place of a dropped sort", () => {
        expect(parseMemberSort("email,asc") ?? DEFAULT_MEMBER_SORT).toEqual(DEFAULT_MEMBER_SORT);
    });
});

describe("withFilters", () => {
    it.each([
        ["search", { q: "wan" }],
        ["role", { role: "ADMIN" }],
        ["status", { status: "REMOVED" }],
        ["sort", { sort: { field: "joinedAt", direction: "desc" } }],
        ["page size", { size: 50 }],
    ] as const)("goes back to the first page when the %s changes", (_, change) => {
        const next = withFilters(params({ page: 4 }), change);

        expect(next).toEqual({ ...params(), ...change, page: 1 });
    });
});

describe("memberPickerQuery", () => {
    it("asks for ten active members by name, starting with the search", () => {
        expect(memberPickerQuery("  wan ")).toEqual({
            q: "wan",
            role: null,
            status: "ACTIVE",
            sort: DEFAULT_MEMBER_SORT,
            page: 0,
            size: 10,
        });
    });
});
