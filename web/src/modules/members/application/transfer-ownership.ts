import type { OrgId, UserId } from "@/shared/domain/ids";
import type { Member } from "../domain/member";
import type { MemberRepository } from "./ports";

/** The target becomes the owner and the caller an admin. Needs a recent sign-in. */
export type TransferOwnership = (orgId: OrgId, userId: UserId) => Promise<Member>;

export function makeTransferOwnership(members: MemberRepository): TransferOwnership {
    return (orgId, userId) => members.transferOwnership(orgId, userId);
}
