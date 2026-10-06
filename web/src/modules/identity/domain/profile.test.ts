import { asUserId } from "@/shared/domain/ids";
import { describe, expect, it } from "vitest";
import { isUnchanged, profileUpdateSchema, renamed, type Profile } from "./profile";

const PROFILE: Profile = {
    userId: asUserId("user-1"),
    email: "amani@kilimalabs.co",
    displayName: "Amani Otieno",
    status: "ACTIVE",
};

describe("profileUpdateSchema", () => {
    it("trims the display name", () => {
        expect(profileUpdateSchema.parse({ displayName: "  Amani O.  " })).toEqual({ displayName: "Amani O." });
    });

    it.each(["", "   "])("refuses %j", (displayName) => {
        expect(profileUpdateSchema.safeParse({ displayName }).error?.issues[0]?.message).toBe("Enter your name");
    });
});

describe("isUnchanged", () => {
    it.each([
        ["Amani Otieno", true],
        ["  Amani Otieno ", true],
        ["Amani O.", false],
        ["   ", false],
    ])("%j leaves the name unchanged: %s", (typed, expected) => {
        expect(isUnchanged(PROFILE, typed)).toBe(expected);
    });
});

describe("renamed", () => {
    it("changes only the display name", () => {
        expect(renamed(PROFILE, { displayName: "Amani O." })).toEqual({ ...PROFILE, displayName: "Amani O." });
    });
});
