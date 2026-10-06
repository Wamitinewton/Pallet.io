import { makeChangeRole, type ChangeRole } from "./change-role";
import { makeGetMyMembership, type GetMyMembership } from "./get-my-membership";
import { makeListMembers, type ListMembers } from "./list-members";
import type { MemberRepository } from "./ports";
import { makeRemoveMember, type RemoveMember } from "./remove-member";
import { makeTransferOwnership, type TransferOwnership } from "./transfer-ownership";

export interface MemberUseCases {
    readonly listMembers: ListMembers;
    readonly getMyMembership: GetMyMembership;
    readonly changeRole: ChangeRole;
    readonly removeMember: RemoveMember;
    readonly transferOwnership: TransferOwnership;
}

export interface MemberDependencies {
    readonly members: MemberRepository;
}

export function makeMemberUseCases({ members }: MemberDependencies): MemberUseCases {
    return {
        listMembers: makeListMembers(members),
        getMyMembership: makeGetMyMembership(members),
        changeRole: makeChangeRole(members),
        removeMember: makeRemoveMember(members),
        transferOwnership: makeTransferOwnership(members),
    };
}
