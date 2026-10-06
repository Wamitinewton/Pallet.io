import { describe, expect, it } from "vitest";
import { meetsPolicy, MIN_PASSWORD_LENGTH, passwordSchema, strength } from "./password-policy";

describe("meetsPolicy", () => {
    it.each([
        ["a".repeat(MIN_PASSWORD_LENGTH - 1), false],
        ["a".repeat(MIN_PASSWORD_LENGTH), true],
        [" ".repeat(MIN_PASSWORD_LENGTH), false],
        ["correct horse battery", true],
    ])("%j meets the policy: %s", (password, meets) => {
        expect(meetsPolicy(password)).toBe(meets);
    });

    it("is what the schema enforces", () => {
        expect(passwordSchema.safeParse("short").error?.issues[0]?.message).toBe("Use at least 12 characters");
        expect(passwordSchema.safeParse("long enough pw").success).toBe(true);
    });
});

describe("strength", () => {
    it.each([
        ["", 0],
        ["Ab1!", 1],
        ["abcdefghijkl", 1],
        ["abcdefghijklmnop", 2],
        ["correct-horse-battery", 3],
        ["Correct-horse-battery-9", 4],
        ["aaaaaaaaaaaaaaaaaaaa", 2],
        ["Aaaa1!xyzwvut", 1],
    ])("scores %j as %i", (password, score) => {
        expect(strength(password)).toBe(score);
    });

    it("never rates a password the policy refuses above one", () => {
        expect(strength("Zx9!Qw8@Er7")).toBe(1);
    });
});
