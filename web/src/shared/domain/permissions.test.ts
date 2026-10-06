import { describe, expect, it } from "vitest";
import {
    can,
    canChangeRoleOf,
    canRemoveMember,
    canTransferOwnershipTo,
    CAPABILITIES,
    grantableInviteRoles,
    MINIMUM_ROLE,
    type Capability,
} from "./permissions";
import { ROLES, type Role } from "./role";

const mirrors: readonly (readonly [Capability, Role, string])[] = [
    ["org.view", "VIEWER", "OrgController.get"],
    ["org.rename", "ADMIN", "OrgController.update"],
    ["org.delete", "OWNER", "OrgController.delete"],
    ["members.view", "VIEWER", "MemberController.list and get"],
    ["members.viewRemoved", "ADMIN", "MembershipPolicy.checkCanViewRemoved"],
    ["members.changeRole", "OWNER", "MemberController.changeRole"],
    ["members.transferOwnership", "OWNER", "MemberController.transferOwnership"],
    ["invites.manage", "ADMIN", "InviteController.create, list, resend and revoke"],
    ["teams.view", "VIEWER", "TeamController.list, get and listMembers"],
    ["teams.manage", "ADMIN", "TeamController.create, rename, delete, addMember and removeMember"],
    ["apps.view", "VIEWER", "AppController.list and get"],
    ["apps.create", "DEVELOPER", "AppController.create"],
    ["apps.update", "DEVELOPER", "AppController.update"],
    ["apps.delete", "ADMIN", "AppController.delete"],
    ["github.installations.view", "VIEWER", "InstallationController.list"],
    ["github.installations.manage", "ADMIN", "InstallationController.startInstall, link and unlink"],
    ["github.repositories.list", "DEVELOPER", "InstallationController.repositories"],
    ["repoLink.view", "VIEWER", "RepoLinkController.get"],
    ["repoLink.tune", "DEVELOPER", "RepoLinkController.update"],
    ["repoLink.link", "ADMIN", "RepoLinkController.link"],
    ["repoLink.takeOverVerification", "ADMIN", "RepoLinkController.takeOverVerification"],
    ["repoLink.disconnect", "ADMIN", "RepoLinkController.disconnect"],
    ["builds.trigger", "DEVELOPER", "RepoLinkController.build"],
];

describe("permission table", () => {
    it("has a mirrored controller method for every capability, and no extra ones", () => {
        expect(mirrors.map(([capability]) => capability).toSorted()).toEqual([...CAPABILITIES].toSorted());
    });

    it.each(mirrors)("%s needs %s, as %s", (capability, minimum) => {
        expect(MINIMUM_ROLE[capability]).toBe(minimum);
        for (const role of ROLES) {
            const allowed = ROLES.indexOf(role) >= ROLES.indexOf(minimum);
            expect(can({ role, orgKind: "TEAM" }, capability)).toBe(allowed);
        }
    });

    it.each([
        ["org.delete", "OrgDeletionService.delete"],
        ["invites.manage", "InviteService.create"],
    ] as const)("never allows %s in a personal organization, as %s", (capability, _source) => {
        expect(can({ role: "OWNER", orgKind: "TEAM" }, capability)).toBe(true);
        expect(can({ role: "OWNER", orgKind: "PERSONAL" }, capability)).toBe(false);
    });

    it("treats a personal organization like any other for every other capability", () => {
        const teamOnly: readonly Capability[] = ["org.delete", "invites.manage"];
        for (const capability of CAPABILITIES.filter((c) => !teamOnly.includes(c))) {
            expect(can({ role: "OWNER", orgKind: "PERSONAL" }, capability)).toBe(true);
        }
    });
});

describe("canRemoveMember, as MembershipPolicy.checkCanRemove", () => {
    it.each([
        ["OWNER", "ADMIN", false, true],
        ["OWNER", "VIEWER", false, true],
        ["OWNER", "OWNER", true, false],
        ["ADMIN", "DEVELOPER", false, true],
        ["ADMIN", "VIEWER", false, true],
        ["ADMIN", "ADMIN", false, false],
        ["ADMIN", "OWNER", false, false],
        ["ADMIN", "ADMIN", true, true],
        ["DEVELOPER", "VIEWER", false, false],
        ["DEVELOPER", "DEVELOPER", true, true],
        ["VIEWER", "VIEWER", true, true],
        ["VIEWER", "DEVELOPER", false, false],
    ] as const)("%s removing %s (self: %s) is %s", (caller, target, isSelf, allowed) => {
        expect(canRemoveMember(caller, target, isSelf)).toBe(allowed);
    });
});

describe("canChangeRoleOf, as MembershipPolicy.checkCanChangeRole", () => {
    it.each([
        ["OWNER", "ADMIN", true],
        ["OWNER", "DEVELOPER", true],
        ["OWNER", "VIEWER", true],
        ["OWNER", "OWNER", false],
        ["ADMIN", "DEVELOPER", false],
        ["ADMIN", "VIEWER", false],
        ["DEVELOPER", "VIEWER", false],
        ["VIEWER", "VIEWER", false],
    ] as const)("%s changing the role of %s is %s", (caller, target, allowed) => {
        expect(canChangeRoleOf(caller, target)).toBe(allowed);
    });
});

describe("canTransferOwnershipTo, as MembershipPolicy.checkCanTransferOwnership", () => {
    it.each([
        ["OWNER", "ADMIN", true],
        ["OWNER", "VIEWER", true],
        ["OWNER", "OWNER", false],
        ["ADMIN", "DEVELOPER", false],
        ["DEVELOPER", "VIEWER", false],
    ] as const)("%s handing ownership to %s is %s", (caller, target, allowed) => {
        expect(canTransferOwnershipTo(caller, target)).toBe(allowed);
    });
});

describe("grantableInviteRoles, as MembershipPolicy.checkCanInvite", () => {
    it.each([
        ["OWNER", ["ADMIN", "DEVELOPER", "VIEWER"]],
        ["ADMIN", ["DEVELOPER", "VIEWER"]],
        ["DEVELOPER", []],
        ["VIEWER", []],
    ] as const)("%s may invite %j", (caller, roles) => {
        expect(grantableInviteRoles(caller)).toEqual(roles);
    });

    it("never grants ownership through an invite", () => {
        for (const role of ROLES) expect(grantableInviteRoles(role)).not.toContain("OWNER");
    });
});
