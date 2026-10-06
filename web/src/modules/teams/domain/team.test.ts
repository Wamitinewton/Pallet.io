import { describe, expect, it } from "vitest";
import {
    confirmsTeamDeletion,
    isSameTeamName,
    newTeamSchema,
    renameTeamSchema,
    teamIdFromParam,
    teamNameSchema,
    withMemberCountChange,
} from "./team";
import { aTeam } from "./testing/fixtures";

describe("teamNameSchema", () => {
    it("trims the name", () => {
        expect(teamNameSchema.parse("  Payments ")).toBe("Payments");
    });

    it.each([
        ["a blank name", "   ", "Enter a name for the team"],
        ["a name over 100 characters", "a".repeat(101), "Keep the name to 100 characters or fewer"],
    ])("refuses %s", (_, name, message) => {
        expect(teamNameSchema.safeParse(name).error?.issues[0]?.message).toBe(message);
    });

    it("accepts 100 characters and characters beyond ASCII", () => {
        expect(teamNameSchema.safeParse("a".repeat(100)).success).toBe(true);
        expect(teamNameSchema.safeParse("Malipo ya Simu · 日本").success).toBe(true);
    });
});

describe("newTeamSchema", () => {
    it("leaves an empty slug out so the backend derives it", () => {
        expect(newTeamSchema.parse({ name: "Data", slug: "  " })).toEqual({ name: "Data", slug: undefined });
    });

    it.each([
        ["data", true],
        ["data-platform", true],
        ["a".repeat(63), true],
        ["a".repeat(64), false],
        ["Data", false],
        ["-data", false],
        ["data-", false],
        ["data platform", false],
    ])("%s is a valid slug: %s", (slug, valid) => {
        expect(newTeamSchema.safeParse({ name: "Data", slug }).success).toBe(valid);
    });
});

describe("renameTeamSchema", () => {
    it("carries the name only, so the slug can never change", () => {
        expect(renameTeamSchema.parse({ name: " Billing ", slug: "billing" })).toEqual({ name: "Billing" });
    });
});

describe("isSameTeamName", () => {
    it("ignores surrounding blanks", () => {
        expect(isSameTeamName(aTeam(), " Payments ")).toBe(true);
        expect(isSameTeamName(aTeam(), "Billing")).toBe(false);
    });
});

describe("confirmsTeamDeletion", () => {
    it("accepts exactly the name", () => {
        expect(confirmsTeamDeletion(aTeam(), "Payments")).toBe(true);
    });

    it.each([
        ["a different case", "payments"],
        ["the slug of a differently named team", "payments-team"],
        ["a trailing space", "Payments "],
        ["nothing", ""],
    ])("refuses %s", (_, typed) => {
        expect(confirmsTeamDeletion(aTeam(), typed)).toBe(false);
    });
});

describe("withMemberCountChange", () => {
    it("moves the count either way, never below zero", () => {
        expect(withMemberCountChange(aTeam({ memberCount: 3 }), 1).memberCount).toBe(4);
        expect(withMemberCountChange(aTeam({ memberCount: 3 }), -1).memberCount).toBe(2);
        expect(withMemberCountChange(aTeam({ memberCount: 0 }), -1).memberCount).toBe(0);
    });
});

describe("teamIdFromParam", () => {
    it("accepts a UUID", () => {
        expect(teamIdFromParam("3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b")).toBe("3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b");
    });

    it.each(["payments", "3f2b8c1e", "3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6bz", ""])("refuses %j", (value) => {
        expect(teamIdFromParam(value)).toBeUndefined();
    });
});
