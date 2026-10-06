import type { InviteId, OrgId } from "@/shared/domain/ids";
import type { Invite } from "../domain/invite";
import { ALL_PENDING_QUERY } from "../domain/invite-list-query";
import type { InviteRepository } from "./ports";

export type ResendInvite = (orgId: OrgId, inviteId: InviteId) => Promise<Invite>;

export function makeResendInvite(invites: InviteRepository): ResendInvite {
    return (orgId, inviteId) => invites.resend(orgId, inviteId);
}

/**
 * Resends whatever invite is pending for `email`, for when creating one answers that it already exists.
 * No endpoint reads an invite by address, so the pending list is searched; it always fits on one page.
 *
 * @returns undefined when nothing is pending for that address any more.
 */
export type ResendPendingInvite = (orgId: OrgId, email: string) => Promise<Invite | undefined>;

export function makeResendPendingInvite(invites: InviteRepository): ResendPendingInvite {
    return async (orgId, email) => {
        const pending = await invites.list(orgId, ALL_PENDING_QUERY);
        const wanted = email.toLowerCase();
        const invite = pending.items.find((candidate) => candidate.email.toLowerCase() === wanted);
        return invite === undefined ? undefined : invites.resend(orgId, invite.id);
    };
}
