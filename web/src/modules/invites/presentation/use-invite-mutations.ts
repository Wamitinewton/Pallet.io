"use client";

import { ApiError } from "@/shared/domain/errors";
import type { InviteId, OrgId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import { messageFor } from "@/shared/presentation/errors";
import { useToast } from "@/shared/presentation/ui";
import { useMutation, useQueryClient, type QueryKey, type UseMutationResult } from "@tanstack/react-query";
import type { RevokeOutcome } from "../application/revoke-invite";
import type { NewInvite } from "../domain/create-invite";
import type { Invite } from "../domain/invite";
import { NO_LONGER_PENDING } from "../domain/invite-errors";
import type { InviteListQuery } from "../domain/invite-list-query";
import { noLongerPendingCopy, resendErrorCopy, resentCopy, revokedCopy, revokeErrorCopy } from "./invite-copy";
import { useInviteUseCases } from "./invite-use-cases";
import { inviteKeys, listQueryOf } from "./queries";

type CachedPages = readonly (readonly [QueryKey, Page<Invite> | undefined])[];

interface ListSnapshot {
    readonly lists: CachedPages;
}

function isNoLongerPending(error: unknown): boolean {
    return error instanceof ApiError && NO_LONGER_PENDING.includes(error.code);
}

function replaced(page: Page<Invite> | undefined, invite: Invite): Page<Invite> | undefined {
    if (page === undefined) return undefined;
    return { ...page, items: page.items.map((item) => (item.id === invite.id ? invite : item)) };
}

/** Gone from a pending list, shown as revoked in the list of every status, and left alone anywhere else. */
function withoutRevoked(
    page: Page<Invite> | undefined,
    query: InviteListQuery | undefined,
    inviteId: InviteId,
): Page<Invite> | undefined {
    if (!page?.items.some((item) => item.id === inviteId)) return page;
    if (query?.status === "PENDING") {
        return {
            ...page,
            items: page.items.filter((item) => item.id !== inviteId),
            totalItems: Math.max(0, page.totalItems - 1),
        };
    }
    if (query?.status === null) {
        return {
            ...page,
            items: page.items.map((item) => (item.id === inviteId ? { ...item, status: "REVOKED" as const } : item)),
        };
    }
    return page;
}

function useRefreshInvites(orgId: OrgId): () => Promise<void> {
    const queryClient = useQueryClient();
    return () => queryClient.invalidateQueries({ queryKey: inviteKeys.lists(orgId) });
}

/** The dialog reports the outcome itself, since most refusals belong on its email field. */
export function useCreateInvite(orgId: OrgId): UseMutationResult<Invite, unknown, NewInvite> {
    const { createInvite } = useInviteUseCases();
    const refresh = useRefreshInvites(orgId);

    return useMutation({
        mutationFn: (invite) => createInvite(orgId, invite),
        onSuccess: refresh,
    });
}

/** For an address that already has a pending invite; answers undefined when nothing is pending for it any more. */
export function useResendPendingInvite(orgId: OrgId): UseMutationResult<Invite | undefined, unknown, string> {
    const { resendPendingInvite } = useInviteUseCases();
    const refresh = useRefreshInvites(orgId);

    return useMutation({
        mutationFn: (email) => resendPendingInvite(orgId, email),
        onSettled: refresh,
    });
}

/**
 * Puts the new expiry and send count on every cached row. The outcome is toasted from here; an invite that
 * stopped being pending meanwhile has its lists read again.
 */
export function useResendInvite(orgId: OrgId): UseMutationResult<Invite, unknown, Invite> {
    const { resendInvite } = useInviteUseCases();
    const queryClient = useQueryClient();
    const refresh = useRefreshInvites(orgId);
    const toast = useToast();

    return useMutation({
        mutationFn: (invite) => resendInvite(orgId, invite.id),
        onSuccess: (invite) => {
            queryClient.setQueriesData<Page<Invite>>({ queryKey: inviteKeys.lists(orgId) }, (page) =>
                replaced(page, invite),
            );
            toast.success(resentCopy(invite.email));
        },
        onError: (error, invite) => {
            toast.error(messageFor(error, resendErrorCopy(invite.email)));
            if (isNoLongerPending(error)) void refresh();
        },
    });
}

/**
 * Takes the invite off every cached pending list at once. A refusal puts the rows back; losing a race with
 * the invitee or another admin says what the invite became. The lists are read again either way.
 */
export function useRevokeInvite(orgId: OrgId): UseMutationResult<RevokeOutcome, unknown, Invite, ListSnapshot> {
    const { revokeInvite } = useInviteUseCases();
    const queryClient = useQueryClient();
    const refresh = useRefreshInvites(orgId);
    const toast = useToast();
    const queryKey = inviteKeys.lists(orgId);

    return useMutation({
        mutationFn: (invite) => revokeInvite(orgId, invite),
        onMutate: async (invite) => {
            await queryClient.cancelQueries({ queryKey });
            const lists = queryClient.getQueriesData<Page<Invite>>({ queryKey });
            for (const [key, page] of lists) {
                queryClient.setQueryData(key, withoutRevoked(page, listQueryOf(key), invite.id));
            }
            return { lists };
        },
        onSuccess: (outcome, invite) => {
            if (outcome.kind === "revoked") toast.success(revokedCopy(invite.email));
            else toast.info(noLongerPendingCopy(invite.email, outcome.current?.status));
        },
        onError: (error, _invite, snapshot) => {
            for (const [key, page] of snapshot?.lists ?? []) queryClient.setQueryData(key, page);
            toast.error(messageFor(error, revokeErrorCopy));
        },
        onSettled: refresh,
    });
}
