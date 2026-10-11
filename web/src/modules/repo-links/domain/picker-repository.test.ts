import { describe, expect, it } from "vitest";
import {
    canPush,
    linkabilityOf,
    permissionRank,
    REPOSITORY_PAGE_SIZE,
    repositoryQuery,
    type RepositoryPermission,
} from "./picker-repository";
import { aPickerRepository } from "./testing/fixtures";

describe("permission ranking", () => {
    it("orders GitHub's permissions from pull to admin, with none below them all", () => {
        const ranked: (RepositoryPermission | null)[] = ["admin", null, "push", "pull", "maintain", "triage"];

        expect(ranked.toSorted((a, b) => permissionRank(a) - permissionRank(b))).toEqual([
            null,
            "pull",
            "triage",
            "push",
            "maintain",
            "admin",
        ]);
    });

    it.each<[RepositoryPermission | null, boolean]>([
        ["admin", true],
        ["maintain", true],
        ["push", true],
        ["triage", false],
        ["pull", false],
        [null, false],
    ])("%s can push: %s", (permission, expected) => {
        expect(canPush(permission)).toBe(expected);
    });
});

describe("linkabilityOf", () => {
    it("takes the backend's word that a repository is linkable", () => {
        expect(linkabilityOf(aPickerRepository())).toEqual({ linkable: true });
    });

    it("says an archived repository is archived, whatever the caller's permission", () => {
        expect(linkabilityOf(aPickerRepository({ archived: true, linkable: false }))).toEqual({
            linkable: false,
            reason: "archived",
        });
    });

    it.each<RepositoryPermission | null>(["triage", "pull", null])(
        "says %s is below what linking needs",
        (permission) => {
            expect(linkabilityOf(aPickerRepository({ permission, linkable: false }))).toEqual({
                linkable: false,
                reason: "permission",
            });
        },
    );

    it("holds back a repository the backend refused for a reason it didn't give", () => {
        expect(linkabilityOf(aPickerRepository({ permission: "admin", linkable: false }))).toEqual({
            linkable: false,
            reason: "unavailable",
        });
    });
});

describe("repositoryQuery", () => {
    it("trims the search and counts pages from zero", () => {
        expect(repositoryQuery("  web ", 3)).toEqual({ q: "web", page: 2, size: REPOSITORY_PAGE_SIZE });
    });

    it.each([0, -1, 1.5, Number.NaN])("reads page %s as the first", (page) => {
        expect(repositoryQuery("", page).page).toBe(0);
    });

    it("cuts a search to the backend's limit", () => {
        expect(repositoryQuery("a".repeat(150), 1).q).toHaveLength(100);
    });
});
