import type { UserId } from "@/shared/domain/ids";
import { canChangeRoleOf, canRemoveMember, canTransferOwnershipTo } from "@/shared/domain/permissions";
import type { Role } from "@/shared/domain/role";
import type { Member } from "./member";

export type MemberAction = "changeRole" | "transferOwnership" | "remove" | "leave";

export interface Viewer {
    readonly userId: UserId;
    readonly role: Role;
}

/** What `viewer` may do to `member`, in the order a menu lists it. The rules themselves live in permissions. */
export function memberActions(member: Member, viewer: Viewer): readonly MemberAction[] {
    if (member.status !== "ACTIVE") return [];
    if (member.userId === viewer.userId) {
        return canRemoveMember(viewer.role, member.role, true) ? ["leave"] : [];
    }

    const actions: MemberAction[] = [];
    if (canChangeRoleOf(viewer.role, member.role)) actions.push("changeRole");
    if (canTransferOwnershipTo(viewer.role, member.role)) actions.push("transferOwnership");
    if (canRemoveMember(viewer.role, member.role, false)) actions.push("remove");
    return actions;
}
