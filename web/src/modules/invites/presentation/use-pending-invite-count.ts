"use client";

import { useOrgAccess } from "@/modules/members";
import type { OrgId } from "@/shared/domain/ids";
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { PENDING_COUNT_QUERY } from "../domain/invite-list-query";
import { useInviteUseCases } from "./invite-use-cases";
import { inviteQueries } from "./queries";

/**
 * How many invites are waiting, or undefined for a caller who can't manage invites, for whom nothing is sent.
 * Cached beneath the invite lists, so sending or revoking one refreshes it.
 */
export function usePendingInviteCount(orgId: OrgId): UseQueryResult<number> | undefined {
    const canManage = useOrgAccess().can("invites.manage");
    const { listInvites } = useInviteUseCases();
    const count = useQuery({
        ...inviteQueries.list(listInvites, orgId, PENDING_COUNT_QUERY),
        enabled: canManage,
        select: (page) => page.totalItems,
    });
    return canManage ? count : undefined;
}
