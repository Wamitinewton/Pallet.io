import type { OrgId } from "@/shared/domain/ids";
import { queryScopes } from "@/shared/presentation/query";
import { queryOptions } from "@tanstack/react-query";
import type { GetMyMembership } from "../application/get-my-membership";
import type { ListMembers } from "../application/list-members";
import { MEMBER_DIRECTORY_QUERY, type MemberListQuery } from "../domain/member-list-query";

export const memberKeys = {
    all: queryScopes.members,
    mine: (orgId: OrgId) => [...memberKeys.all(orgId), "me"] as const,
    /** Every page of every list and picker, so a change to one member reaches all of them. */
    lists: (orgId: OrgId) => [...memberKeys.all(orgId), "list"] as const,
    list: (orgId: OrgId, query: MemberListQuery) => [...memberKeys.lists(orgId), query] as const,
};

export const memberQueries = {
    mine: (getMyMembership: GetMyMembership, orgId: OrgId) =>
        queryOptions({
            queryKey: memberKeys.mine(orgId),
            queryFn: () => getMyMembership(orgId),
        }),
    list: (listMembers: ListMembers, orgId: OrgId, query: MemberListQuery) =>
        queryOptions({
            queryKey: memberKeys.list(orgId, query),
            queryFn: () => listMembers(orgId, query),
        }),
    directory: (listMembers: ListMembers, orgId: OrgId) =>
        memberQueries.list(listMembers, orgId, MEMBER_DIRECTORY_QUERY),
};
