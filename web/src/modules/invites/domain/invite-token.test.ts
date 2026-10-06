import { describe, expect, it } from "vitest";
import { MAX_INVITE_TOKEN_LENGTH, parseInviteToken, readTokenHints, type InviteToken } from "./invite-token";
import { anInviteToken } from "./testing/fixtures";

describe("parseInviteToken", () => {
    it("accepts a compact JWT", () => {
        const token = anInviteToken();

        expect(parseInviteToken(token)).toBe(token);
    });

    it.each([
        ["nothing", undefined],
        ["an array", [anInviteToken()]],
        ["two segments", "aGVhZGVy.cGF5bG9hZA"],
        ["padding", "aGVhZGVy.cGF5bG9hZA==.c2ln"],
        ["a path", "a.b.c/../d"],
        ["anything longer than a token can be", `a.b.${"c".repeat(MAX_INVITE_TOKEN_LENGTH)}`],
    ])("refuses %s", (_, value) => {
        expect(parseInviteToken(value)).toBeUndefined();
    });
});

describe("readTokenHints", () => {
    it("reads the link's expiry and organization without verifying anything", () => {
        expect(readTokenHints(anInviteToken())).toEqual({
            expiresAt: new Date("2026-01-17T12:00:00Z"),
            orgId: "org-kilima",
        });
    });

    it("reads no more than it needs, never the invited address", () => {
        expect(Object.keys(readTokenHints(anInviteToken()))).toEqual(["expiresAt", "orgId"]);
    });

    it("drops a claim that isn't what it claims to be", () => {
        expect(readTokenHints(anInviteToken({ exp: "tomorrow", orgId: 42 }))).toEqual({
            expiresAt: undefined,
            orgId: undefined,
        });
    });

    it("reads nothing from a payload that isn't JSON", () => {
        expect(readTokenHints("aGVhZGVy.bm90LWpzb24.c2ln" as InviteToken)).toEqual({
            expiresAt: undefined,
            orgId: undefined,
        });
    });
});
