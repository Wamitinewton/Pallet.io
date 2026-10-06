import type { OrgId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import type { Member } from "../domain/member";
import type { MemberListQuery } from "../domain/member-list-query";
import type { MemberRepository } from "./ports";

export type ListMembers = (orgId: OrgId, query: MemberListQuery) => Promise<Page<Member>>;

export function makeListMembers(members: MemberRepository): ListMembers {
    return (orgId, query) => members.list(orgId, query);
}
