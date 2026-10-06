import { describe, expect, it } from "vitest";
import { MAX_SLUG_LENGTH, SLUG_PATTERN, slugFromName } from "./slug";

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

    it("truncates without leaving a trailing hyphen", () => {
        const slug = slugFromName(`${"a".repeat(62)} b`);

        expect(slug).toBe("a".repeat(62));
        expect(SLUG_PATTERN.test(slug)).toBe(true);
        expect(slugFromName("x".repeat(80))).toHaveLength(MAX_SLUG_LENGTH);
    });
});
