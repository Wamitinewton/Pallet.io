"use client";

import type { OrgId, UserId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import { useQuery } from "@tanstack/react-query";
import type { Member } from "../domain/member";
import { useMemberUseCases } from "./member-use-cases";
import { memberQueries } from "./queries";

export type MemberDirectory = ReadonlyMap<UserId, Member>;

function byUserId(page: Page<Member>): MemberDirectory {
    return new Map(page.items.map((member) => [member.userId, member]));
}

/** Active members by id, owners and admins first; undefined until read. Someone missing has most likely left. */
export function useMemberDirectory(orgId: OrgId): MemberDirectory | undefined {
    const { listMembers } = useMemberUseCases();
    return useQuery({ ...memberQueries.directory(listMembers, orgId), select: byUserId }).data;
}
