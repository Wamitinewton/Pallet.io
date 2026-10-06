import type { OrgId } from "@/shared/domain/ids";
import type { AssignableRole, Member } from "../domain/member";
import type { MemberRepository } from "./ports";

export interface ChangeRoleCommand {
    readonly orgId: OrgId;
    readonly member: Member;
    readonly role: AssignableRole;
}

/** Setting the role someone already has changes nothing, so it is never sent. */
export type ChangeRole = (command: ChangeRoleCommand) => Promise<Member>;

export function makeChangeRole(members: MemberRepository): ChangeRole {
    return async ({ orgId, member, role }) => {
        if (member.role === role) return member;
        return members.changeRole(orgId, member.userId, role);
    };
}
