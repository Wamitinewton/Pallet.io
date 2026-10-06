import type { InviteId, UserId } from "@/shared/domain/ids";
import { instantToDate, type IsoInstant } from "@/shared/domain/instant";
import { grantableInviteRoles } from "@/shared/domain/permissions";
import type { Role } from "@/shared/domain/role";

export const INVITE_STATUSES = ["PENDING", "ACCEPTED", "REVOKED", "EXPIRED"] as const;

export type InviteStatus = (typeof INVITE_STATUSES)[number];

/** Nobody is ever invited as the owner. */
export const INVITABLE_ROLES = ["ADMIN", "DEVELOPER", "VIEWER"] as const satisfies readonly Role[];

export type InvitableRole = (typeof INVITABLE_ROLES)[number];

/**
 * Mirrors `orgteam.invites` in `config-repo/org-team-service.yml`, for the copy that explains the limits.
 * The backend enforces them and stays the authority.
 */
export const INVITE_LIMITS = {
    ttlDays: 3,
    maxSends: 4,
    resendCooldownMinutes: 5,
} as const;

/** The token and the accept link never leave the backend; the invitee gets them by email only. */
export interface Invite {
    readonly id: InviteId;
    readonly email: string;
    readonly role: Role;
    readonly status: InviteStatus;
    readonly invitedByUserId: UserId;
    readonly sendCount: number;
    readonly expiresAt: IsoInstant;
    readonly createdAt: IsoInstant;
}

export function isInvitableRole(value: string): value is InvitableRole {
    return (INVITABLE_ROLES as readonly string[]).includes(value);
}

/** Pending on the backend, but past its expiry by `now`: the sweep just hasn't marked it yet. */
export function hasLapsed(invite: Invite, now: Date): boolean {
    return invite.status === "PENDING" && instantToDate(invite.expiresAt).getTime() <= now.getTime();
}

/** Only a live invite can be resent or revoked. */
export function isActionable(invite: Invite, now: Date): boolean {
    return invite.status === "PENDING" && !hasLapsed(invite, now);
}

/** The status a person should see, which turns to expired the moment the link stops working. */
export function displayStatus(invite: Invite, now: Date): InviteStatus {
    return hasLapsed(invite, now) ? "EXPIRED" : invite.status;
}

/** Resending and revoking are checked like inviting: an admin can't act on an invite that grants admin. */
export function canManageInvite(caller: Role, invite: Invite): boolean {
    return grantableInviteRoles(caller).includes(invite.role);
}

/** Someone whose invite lapsed or was withdrawn can be invited afresh. */
export function canInviteAgain(invite: Invite, now: Date): boolean {
    const status = displayStatus(invite, now);
    return status === "EXPIRED" || status === "REVOKED";
}
