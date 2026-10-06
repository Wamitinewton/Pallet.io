import { describe, expect, it } from "vitest";
import {
    createInviteSchema,
    defaultInviteRole,
    ENTER_EMAIL_MESSAGE,
    INVALID_EMAIL_MESSAGE,
    invitableRolesFor,
    ROLE_NOT_GRANTABLE_MESSAGE,
} from "./create-invite";

const messages = (result: { error?: { issues: readonly { message: string }[] } }) =>
    result.error?.issues.map((issue) => issue.message) ?? [];

describe("createInviteSchema", () => {
    it("trims and lowercases the address, as the backend stores it", () => {
        expect(createInviteSchema("OWNER").parse({ email: "  Mercy@KilimaLabs.co ", role: "ADMIN" })).toEqual({
            email: "mercy@kilimalabs.co",
            role: "ADMIN",
        });
    });

    it("asks for an address before checking its shape", () => {
        expect(messages(createInviteSchema("OWNER").safeParse({ email: "  ", role: "VIEWER" }))).toEqual([
            ENTER_EMAIL_MESSAGE,
        ]);
        expect(messages(createInviteSchema("OWNER").safeParse({ email: "mercy@", role: "VIEWER" }))).toEqual([
            INVALID_EMAIL_MESSAGE,
        ]);
    });

    it("refuses an address longer than any mailbox", () => {
        const email = `${"a".repeat(250)}@x.co`;

        expect(messages(createInviteSchema("OWNER").safeParse({ email, role: "VIEWER" }))).toEqual([
            INVALID_EMAIL_MESSAGE,
        ]);
    });

    it("refuses a role the caller can't grant", () => {
        expect(messages(createInviteSchema("ADMIN").safeParse({ email: "a@b.co", role: "ADMIN" }))).toEqual([
            ROLE_NOT_GRANTABLE_MESSAGE,
        ]);
        expect(messages(createInviteSchema("OWNER").safeParse({ email: "a@b.co", role: "OWNER" }))).toEqual([
            ROLE_NOT_GRANTABLE_MESSAGE,
        ]);
    });
});

describe("invitableRolesFor", () => {
    it.each([
        ["OWNER", ["ADMIN", "DEVELOPER", "VIEWER"]],
        ["ADMIN", ["DEVELOPER", "VIEWER"]],
        ["DEVELOPER", []],
    ] as const)("%s may offer %j", (caller, roles) => {
        expect(invitableRolesFor(caller)).toEqual(roles);
    });
});

describe("defaultInviteRole", () => {
    it("starts on developer for anyone who can invite", () => {
        expect(defaultInviteRole("OWNER")).toBe("DEVELOPER");
        expect(defaultInviteRole("ADMIN")).toBe("DEVELOPER");
    });

    it("has nothing to offer a viewer", () => {
        expect(defaultInviteRole("VIEWER")).toBeUndefined();
    });
});
