import { describe, expect, it } from "vitest";
import { isValidSlug, MAX_SLUG_LENGTH, signupSchema, slugFromName, slugProblem, suggestSlugs } from "./signup";

const details = {
    organizationName: " Kilima Labs ",
    slug: "kilima-labs",
    displayName: " Amani Otieno ",
    email: " amani@kilimalabs.co ",
    password: "correct horse battery",
};

describe("slug rules", () => {
    it.each([
        ["a", true],
        ["a-b", true],
        ["kilima-labs-2", true],
        ["-a", false],
        ["a-", false],
        ["a--b", false],
        ["Kilima", false],
        ["kilima labs", false],
        ["kilimá", false],
        ["日本", false],
        ["a".repeat(MAX_SLUG_LENGTH), true],
        ["a".repeat(MAX_SLUG_LENGTH + 1), false],
        ["", false],
    ])("%j is valid: %s", (slug, valid) => {
        expect(isValidSlug(slug)).toBe(valid);
    });

    it("explains each kind of problem", () => {
        expect(slugProblem("")).toBe("Choose a URL for your organization");
        expect(slugProblem("a".repeat(64))).toBe("Keep the URL to 63 characters or fewer");
        expect(slugProblem("a--b")).toMatch(/single hyphens/);
        expect(slugProblem("a-b")).toBeUndefined();
    });
});

describe("slugFromName", () => {
    it.each([
        ["Kilima Labs!", "kilima-labs"],
        ["  Kilima   Labs  ", "kilima-labs"],
        ["Café Nyota", "cafe-nyota"],
        ["ACME--Corp", "acme-corp"],
        ["!!!", ""],
        ["日本", ""],
    ])("%j becomes %j", (name, slug) => {
        expect(slugFromName(name)).toBe(slug);
    });

    it("truncates to a valid slug", () => {
        const slug = slugFromName(`${"a".repeat(62)} b`);

        expect(slug).toBe("a".repeat(62));
        expect(slugFromName("x".repeat(80))).toHaveLength(MAX_SLUG_LENGTH);
        expect(isValidSlug(slug)).toBe(true);
    });

    it("leaves the slug empty for a name with nothing usable, so the field asks for one", () => {
        expect(slugProblem(slugFromName("!!!"))).toBe("Choose a URL for your organization");
    });
});

describe("suggestSlugs", () => {
    it("offers the name's slug first, then a suffixed one", () => {
        expect(suggestSlugs("kilima", "Kilima Labs")).toEqual(["kilima-labs", "kilima-hq"]);
    });

    it("never offers the taken slug or an invalid one", () => {
        expect(suggestSlugs("kilima-labs", "Kilima Labs")).toEqual(["kilima-labs-hq", "kilima-labs-team"]);
        expect(suggestSlugs("a".repeat(62), "!!!")).toEqual([]);
    });
});

describe("signupSchema", () => {
    it("trims the free-text fields and keeps the password as typed", () => {
        expect(signupSchema.parse({ ...details, password: " correct horse battery " })).toEqual({
            organizationName: "Kilima Labs",
            slug: "kilima-labs",
            displayName: "Amani Otieno",
            email: "amani@kilimalabs.co",
            password: " correct horse battery ",
        });
    });

    it("names every missing field", () => {
        const result = signupSchema.safeParse({
            organizationName: " ",
            slug: "",
            displayName: "",
            email: "",
            password: "short",
        });

        const firstMessages = new Map<PropertyKey | undefined, string>();
        for (const issue of result.error?.issues ?? []) {
            if (!firstMessages.has(issue.path[0])) firstMessages.set(issue.path[0], issue.message);
        }

        expect(Object.fromEntries(firstMessages)).toEqual({
            organizationName: "Enter your organization's name",
            slug: "Choose a URL for your organization",
            displayName: "Enter your name",
            email: "Enter your email address",
            password: "Use at least 12 characters",
        });
    });
});
