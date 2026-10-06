"use client";

import { useStepUp } from "@/modules/session";
import { ApiError } from "@/shared/domain/errors";
import type { OrgId, UserId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import { messageFor } from "@/shared/presentation/errors";
import { queryScopes } from "@/shared/presentation/query";
import { useToast } from "@/shared/presentation/ui";
import { useMutation, useQueryClient, type QueryKey, type UseMutationResult } from "@tanstack/react-query";
import { withRole, type AssignableRole, type Member } from "../domain/member";
import { MEMBER_NOT_FOUND } from "../domain/member-errors";
import { roleChangedCopy, roleNotChangedCopy } from "./member-copy";
import { useMemberUseCases } from "./member-use-cases";
import { memberKeys } from "./queries";

export interface RoleChange {
    readonly member: Member;
    readonly role: AssignableRole;
}

interface ListSnapshot {
    readonly lists: readonly (readonly [QueryKey, Page<Member> | undefined])[];
}

function replaced(page: Page<Member> | undefined, member: Member): Page<Member> | undefined {
    if (page === undefined) return undefined;
    return { ...page, items: page.items.map((item) => (item.userId === member.userId ? member : item)) };
}

/**
 * Shows the new role on every cached list at once. A refusal puts the old rows back, and the lists are read
 * again either way, since a refusal usually means someone else changed the membership first. The outcome is
 * toasted from here, so it is reported even after the dialog that started it has gone.
 */
export function useChangeRole(orgId: OrgId): UseMutationResult<Member, unknown, RoleChange, ListSnapshot> {
    const { changeRole } = useMemberUseCases();
    const queryClient = useQueryClient();
    const toast = useToast();
    const queryKey = memberKeys.lists(orgId);

    return useMutation({
        mutationFn: ({ member, role }) => changeRole({ orgId, member, role }),
        onMutate: async ({ member, role }) => {
            await queryClient.cancelQueries({ queryKey });
            const lists = queryClient.getQueriesData<Page<Member>>({ queryKey });
            queryClient.setQueriesData<Page<Member>>({ queryKey }, (page) => replaced(page, withRole(member, role)));
            return { lists };
        },
        onSuccess: (member) => {
            queryClient.setQueriesData<Page<Member>>({ queryKey }, (page) => replaced(page, member));
            toast.success(roleChangedCopy(member.displayName, member.role));
        },
        onError: (error, { member }, snapshot) => {
            for (const [key, page] of snapshot?.lists ?? []) queryClient.setQueryData(key, page);
            toast.error(messageFor(error, roleNotChangedCopy(member.displayName)));
        },
        onSettled: () => queryClient.invalidateQueries({ queryKey }),
    });
}

/** The member's team assignments go with them, so the teams and the organization's counts change too. */
export function useRemoveMember(orgId: OrgId): UseMutationResult<void, unknown, Member> {
    const { removeMember } = useMemberUseCases();
    const queryClient = useQueryClient();

    const refresh = () =>
        Promise.all([
            queryClient.invalidateQueries({ queryKey: memberKeys.lists(orgId) }),
            queryClient.invalidateQueries({ queryKey: queryScopes.organization(orgId) }),
            queryClient.invalidateQueries({ queryKey: queryScopes.teams(orgId) }),
        ]);

    return useMutation({
        mutationFn: (member) => removeMember(orgId, member.userId),
        onSuccess: refresh,
        onError: (error) => {
            if (error instanceof ApiError && error.is(MEMBER_NOT_FOUND)) void refresh();
        },
    });
}

/** Nothing under the organization can be read once the caller has left it, so its cache is dropped whole. */
export function useLeaveOrganization(orgId: OrgId): UseMutationResult<void, unknown, UserId> {
    const { removeMember } = useMemberUseCases();
    const queryClient = useQueryClient();

    return useMutation({
        mutationFn: (myUserId) => removeMember(orgId, myUserId),
        onSuccess: async () => {
            const scope = queryScopes.org(orgId);
            await queryClient.cancelQueries({ queryKey: scope });
            queryClient.removeQueries({ queryKey: scope });
            void queryClient.invalidateQueries({ queryKey: queryScopes.myOrganizations() });
        },
    });
}

/**
 * Runs through step-up. The caller's own role changes with the transfer, so `members/me` is read again and
 * every permission on screen follows it.
 */
export function useTransferOwnership(orgId: OrgId): UseMutationResult<Member, unknown, Member> {
    const { transferOwnership } = useMemberUseCases();
    const { runWithStepUp } = useStepUp();
    const queryClient = useQueryClient();

    return useMutation({
        mutationFn: (member) => runWithStepUp(() => transferOwnership(orgId, member.userId)),
        onSuccess: () =>
            Promise.all([
                queryClient.invalidateQueries({ queryKey: memberKeys.all(orgId) }),
                queryClient.invalidateQueries({ queryKey: queryScopes.myOrganizations() }),
            ]),
    });
}
