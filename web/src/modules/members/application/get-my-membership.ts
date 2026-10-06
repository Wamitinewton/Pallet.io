import type { OrgId } from "@/shared/domain/ids";
import type { Member } from "../domain/member";
import type { MemberRepository } from "./ports";

/** The caller's own membership: the only source of their role in `orgId`. */
export type GetMyMembership = (orgId: OrgId) => Promise<Member>;

export function makeGetMyMembership(members: MemberRepository): GetMyMembership {
    return (orgId) => members.getMine(orgId);
}
