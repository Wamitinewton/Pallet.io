import { describe, expect, it } from "vitest";
import { DEFAULT_INVITE_LIST_PARAMS, INVITE_PAGE_SIZE, inviteListQuery } from "./invite-list-query";

const owner = { role: "OWNER", orgKind: "TEAM" } as const;

describe("inviteListQuery", () => {
    it("asks for the first page of pending invites by default", () => {
        expect(inviteListQuery(DEFAULT_INVITE_LIST_PARAMS, owner)).toEqual({
            status: "PENDING",
            page: 0,
            size: INVITE_PAGE_SIZE,
        });
    });

    it("asks for every status under All, and counts pages from zero", () => {
        expect(inviteListQuery({ inviteStatus: "ALL", invitePage: 3 }, owner)).toEqual({
            status: null,
            page: 2,
            size: INVITE_PAGE_SIZE,
        });
    });

    it("reads a nonsense page as the first", () => {
        expect(inviteListQuery({ inviteStatus: "REVOKED", invitePage: -4 }, owner)?.page).toBe(0);
        expect(inviteListQuery({ inviteStatus: "REVOKED", invitePage: 1.5 }, owner)?.page).toBe(0);
    });

    it.each([
        ["a developer", { role: "DEVELOPER", orgKind: "TEAM" }],
        ["the owner of a personal organization", { role: "OWNER", orgKind: "PERSONAL" }],
        ["someone whose role isn't known yet", undefined],
    ] as const)("asks nothing for %s", (_, viewer) => {
        expect(inviteListQuery(DEFAULT_INVITE_LIST_PARAMS, viewer)).toBeUndefined();
    });
});
