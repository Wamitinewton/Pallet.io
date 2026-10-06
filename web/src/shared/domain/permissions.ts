import type { OrgKind } from "./org-kind";
import { atLeast, type Role } from "./role";

/** Mirrors every `@PreAuthorize` in org-team-service and git-integration-service. */
export const MINIMUM_ROLE = {
    "org.view": "VIEWER",
    "org.rename": "ADMIN",
    "org.delete": "OWNER",
    "members.view": "VIEWER",
    "members.viewRemoved": "ADMIN",
    "members.changeRole": "OWNER",
    "members.transferOwnership": "OWNER",
    "invites.manage": "ADMIN",
    "teams.view": "VIEWER",
    "teams.manage": "ADMIN",
    "apps.view": "VIEWER",
    "apps.create": "DEVELOPER",
    "apps.update": "DEVELOPER",
    "apps.delete": "ADMIN",
    "github.installations.view": "VIEWER",
    "github.installations.manage": "ADMIN",
    "github.repositories.list": "DEVELOPER",
    "repoLink.view": "VIEWER",
    "repoLink.tune": "DEVELOPER",
    "repoLink.link": "ADMIN",
    "repoLink.takeOverVerification": "ADMIN",
    "repoLink.disconnect": "ADMIN",
    "builds.trigger": "DEVELOPER",
} as const satisfies Record<string, Role>;

export type Capability = keyof typeof MINIMUM_ROLE;

export const CAPABILITIES = Object.keys(MINIMUM_ROLE) as readonly Capability[];

export interface OrgAccessContext {
    readonly role: Role;
    readonly orgKind: OrgKind;
}

const TEAM_ONLY: ReadonlySet<Capability> = new Set(["org.delete", "invites.manage"]);

export function can({ role, orgKind }: OrgAccessContext, capability: Capability): boolean {
    if (!atLeast(role, MINIMUM_ROLE[capability])) return false;
    return orgKind === "TEAM" || !TEAM_ONLY.has(capability);
}

/** The owner can never be removed; anyone else may leave, and admins remove only those below them. */
export function canRemoveMember(caller: Role, target: Role, isSelf: boolean): boolean {
    if (target === "OWNER") return false;
    if (isSelf || caller === "OWNER") return true;
    return caller === "ADMIN" && !atLeast(target, "ADMIN");
}

/** Only the owner changes roles, and never the owner's own: ownership moves only by transfer. */
export function canChangeRoleOf(caller: Role, target: Role): boolean {
    return caller === "OWNER" && target !== "OWNER";
}

/** The owner hands ownership to any other active member; the caller then becomes an admin. */
export function canTransferOwnershipTo(caller: Role, target: Role): boolean {
    return caller === "OWNER" && target !== "OWNER";
}

export function grantableInviteRoles(caller: Role): readonly Role[] {
    switch (caller) {
        case "OWNER":
            return ["ADMIN", "DEVELOPER", "VIEWER"];
        case "ADMIN":
            return ["DEVELOPER", "VIEWER"];
        case "DEVELOPER":
        case "VIEWER":
            return [];
    }
}
