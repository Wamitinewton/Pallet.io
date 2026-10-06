import { asUserId } from "@/shared/domain/ids";
import type { Role } from "@/shared/domain/role";
import { describe, expect, it } from "vitest";
import { memberActions, type MemberAction } from "./member-actions";
import { aMember } from "./testing/fixtures";

const ME = asUserId("user-me");
const OTHER = asUserId("user-other");

const actionsOn = (caller: Role, target: Role, self = false): readonly MemberAction[] =>
    memberActions(aMember({ userId: self ? ME : OTHER, role: target }), { userId: ME, role: caller });

describe("memberActions", () => {
    it.each([
        ["OWNER", "ADMIN", ["changeRole", "transferOwnership", "remove"]],
        ["OWNER", "VIEWER", ["changeRole", "transferOwnership", "remove"]],
        ["ADMIN", "OWNER", []],
        ["ADMIN", "ADMIN", []],
        ["ADMIN", "DEVELOPER", ["remove"]],
        ["ADMIN", "VIEWER", ["remove"]],
        ["DEVELOPER", "VIEWER", []],
        ["VIEWER", "DEVELOPER", []],
    ] as const)("offers a %s exactly what they may do to a %s", (caller, target, expected) => {
        expect(actionsOn(caller, target)).toEqual(expected);
    });

    it.each(["ADMIN", "DEVELOPER", "VIEWER"] as const)("lets a %s leave from their own row", (role) => {
        expect(actionsOn(role, role, true)).toEqual(["leave"]);
    });

    it("offers the owner nothing on their own row: ownership has to move first", () => {
        expect(actionsOn("OWNER", "OWNER", true)).toEqual([]);
    });

    it("offers nothing on a removed member", () => {
        const removed = aMember({ userId: OTHER, role: "VIEWER", status: "REMOVED" });

        expect(memberActions(removed, { userId: ME, role: "OWNER" })).toEqual([]);
    });
});
