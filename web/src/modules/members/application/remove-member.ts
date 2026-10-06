import type { OrgId, UserId } from "@/shared/domain/ids";
import type { MemberRepository } from "./ports";

/** Removing your own membership is leaving the organization; the backend tells the two apart by the caller. */
export type RemoveMember = (orgId: OrgId, userId: UserId) => Promise<void>;

export function makeRemoveMember(members: MemberRepository): RemoveMember {
    return (orgId, userId) => members.remove(orgId, userId);
}
