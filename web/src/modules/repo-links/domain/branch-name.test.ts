import { describe, expect, it } from "vitest";
import {
    BRANCH_PATTERN,
    branchNameSchema,
    branchNameViolation,
    ENTER_BRANCH_MESSAGE,
    optionalBranchNameSchema,
} from "./branch-name";

/** The backend's own examples (`RepoLinkControllerIntegrationTest`, `PayloadParserTest`), run through both rules. */
const VALID = ["main", "release/1.2", "feature/x-y_z", "v1.0.0", "a.lock.b", "user@host"];
const INVALID = [
    "-x",
    "a..b",
    "x.lock",
    "x.lock/child",
    "a//b",
    "/main",
    "trailing/",
    "ends.",
    ".hidden",
    "dir/.hidden",
    "at@{brace",
    "@",
    "has space",
    "tab\there",
    "a~1",
    "a^b",
    "a:b",
    "a?b",
    "a*b",
    "a[b",
    "a\\b",
    "del\u007f",
];

describe("branch name rules", () => {
    it.each(VALID)("accept %j, by the pattern and by rule", (name) => {
        expect(BRANCH_PATTERN.test(name)).toBe(true);
        expect(branchNameViolation(name)).toBeUndefined();
    });

    it.each(INVALID)("refuse %j, by the pattern and by rule", (name) => {
        expect(BRANCH_PATTERN.test(name)).toBe(false);
        expect(branchNameViolation(name)).toBeDefined();
    });

    it("counts the limit in bytes, which the pattern can't", () => {
        const twoByteCharacters = "é".repeat(128);

        expect(BRANCH_PATTERN.test(twoByteCharacters)).toBe(true);
        expect(branchNameViolation(twoByteCharacters)).toBe("must be at most 255 bytes");
        expect(branchNameViolation("a".repeat(255))).toBeUndefined();
    });

    it.each([
        ["", "must name a branch or tag"],
        ["-x", "must not start with '-'"],
        ["a..b", "must not contain '..'"],
        ["a@{b", "must not contain '@{' or be '@'"],
        ["a b", "must not contain control characters, spaces, or any of ~^:?*[\\"],
        ["a//b", "must not start or end with '/' or contain '//'"],
        ["ends.", "must not end with '.'"],
        ["x.lock", "no component may start with '.' or end with '.lock'"],
    ])("names the first rule %j breaks as the backend does", (name, violation) => {
        expect(branchNameViolation(name)).toBe(violation);
    });
});

describe("branchNameSchema", () => {
    it("trims what was typed", () => {
        expect(branchNameSchema.parse("  release/1.2 ")).toBe("release/1.2");
    });

    it("asks for a branch when there is none", () => {
        expect(branchNameSchema.safeParse("  ").error?.issues[0]?.message).toBe(ENTER_BRANCH_MESSAGE);
    });

    it("says which rule a name breaks", () => {
        expect(branchNameSchema.safeParse("a..b").error?.issues[0]?.message).toBe("Must not contain '..'.");
    });
});

describe("optionalBranchNameSchema", () => {
    it("reads an empty branch as none", () => {
        expect(optionalBranchNameSchema.parse("   ")).toBeUndefined();
    });

    it("still refuses a bad one", () => {
        expect(optionalBranchNameSchema.safeParse("x.lock").success).toBe(false);
    });
});
