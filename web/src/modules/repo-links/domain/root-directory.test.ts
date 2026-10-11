import { describe, expect, it } from "vitest";
import {
    normalizeRootDirectory,
    ROOT_DIRECTORY_PATTERN,
    rootDirectorySchema,
    rootDirectoryViolation,
} from "./root-directory";

/** The backend's own examples (`RootDirectoryTest`), run through both rules. */
const VALID = ["", "apps/web", "apps/web/", "web", "services/api-v2", ".github", "a".repeat(255)];
const INVALID = [
    "/apps/web",
    "/",
    "../x",
    "../web",
    "apps/../web",
    "apps/..",
    "apps/web..old",
    "./apps",
    "apps/./web",
    ".",
    "apps\\web",
    "a//b",
    "apps//web",
    "apps/web//",
    "apps/\u0000web",
    "apps/\nweb",
    "apps/\u007fweb",
    "apps/\u0085web",
    "a".repeat(256),
];

describe("root directory rules", () => {
    it.each(VALID)("accept %j, by the pattern and by rule", (value) => {
        expect(ROOT_DIRECTORY_PATTERN.test(value)).toBe(true);
        expect(rootDirectoryViolation(value)).toBeUndefined();
    });

    it.each(INVALID)("refuse %j, by the pattern and by rule", (value) => {
        expect(ROOT_DIRECTORY_PATTERN.test(value)).toBe(false);
        expect(rootDirectoryViolation(value)).toBeDefined();
    });

    it.each([
        ["a".repeat(256), "must be at most 255 characters"],
        ["/apps", "must be relative to the repository root"],
        ["apps\\web", "must not contain a backslash"],
        ["apps/\nweb", "must not contain control characters"],
        ["../x", "must not contain '..'"],
        ["a//b", "must not contain an empty segment"],
        ["apps/./web", "must not contain a '.' segment"],
    ])("names the first rule %j breaks as the backend does", (value, violation) => {
        expect(rootDirectoryViolation(value)).toBe(violation);
    });
});

describe("normalizeRootDirectory", () => {
    it("drops a trailing slash and keeps the root empty, as the server does", () => {
        expect(normalizeRootDirectory("apps/web/")).toBe("apps/web");
        expect(normalizeRootDirectory("apps/web")).toBe("apps/web");
        expect(normalizeRootDirectory("")).toBe("");
    });
});

describe("rootDirectorySchema", () => {
    it("reads empty as the repository root", () => {
        expect(rootDirectorySchema.parse("  ")).toBe("");
    });

    it("normalizes what it accepts", () => {
        expect(rootDirectorySchema.parse("apps/web/")).toBe("apps/web");
    });

    it("says which rule a directory breaks", () => {
        expect(rootDirectorySchema.safeParse("../x").error?.issues[0]?.message).toBe("Must not contain '..'.");
    });
});
