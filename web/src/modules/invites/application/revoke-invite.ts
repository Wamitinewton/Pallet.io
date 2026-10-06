import { ApiError } from "@/shared/domain/errors";
import type { OrgId } from "@/shared/domain/ids";
import type { Invite } from "../domain/invite";
import { NO_LONGER_PENDING } from "../domain/invite-errors";
import { RECENT_INVITES_QUERY } from "../domain/invite-list-query";
import type { InviteRepository } from "./ports";

export type RevokeOutcome =
    | { readonly kind: "revoked" }
    /** It was accepted, revoked or lapsed first. `current` is what it became, if it is still among the most recent. */
    | { readonly kind: "no-longer-pending"; readonly current: Invite | undefined };

export type RevokeInvite = (orgId: OrgId, invite: Invite) => Promise<RevokeOutcome>;

/** Losing a race with the invitee or another admin isn't a failure; the outcome says what happened instead. */
export function makeRevokeInvite(invites: InviteRepository): RevokeInvite {
    return async (orgId, invite) => {
        try {
            await invites.revoke(orgId, invite.id);
            return { kind: "revoked" };
        } catch (error) {
            if (!(error instanceof ApiError && NO_LONGER_PENDING.includes(error.code))) throw error;
            const recent = await invites.list(orgId, RECENT_INVITES_QUERY).catch(() => undefined);
            return {
                kind: "no-longer-pending",
                current: recent?.items.find((candidate) => candidate.id === invite.id),
            };
        }
    };
}
