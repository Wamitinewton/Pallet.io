import type { IsoInstant } from "@/shared/domain/instant";
import type { Role } from "@/shared/domain/role";

/** What an emailed invite is for, as anyone holding its link may see it. The invitee's address arrives masked. */
export interface InvitePreview {
    readonly orgName: string;
    readonly role: Role;
    readonly inviterName: string;
    readonly maskedEmail: string;
    readonly expiresAt: IsoInstant;
}

/** What the invitee will be able to do, in the words `invite.html` uses. */
export const INVITED_ROLE_DESCRIPTION: Readonly<Record<Role, string>> = {
    OWNER: "Owners can do everything an admin can, plus change roles and delete the organization.",
    ADMIN: "Admins can invite people, manage teams, connect GitHub and link repositories.",
    DEVELOPER: "Developers can create and edit apps, change build settings and start builds.",
    VIEWER: "Viewers can see everything in the organization and change nothing.",
};

export type UnavailableReason = "expired" | "withdrawn";

/**
 * The backend answers a revoked, accepted and expired invite alike, so expiry is told apart by the clock
 * when the link's expiry is known; otherwise the general "no longer valid" is true either way.
 */
export function unavailableReason(expiresAt: Date | undefined, now: Date): UnavailableReason {
    return expiresAt !== undefined && expiresAt.getTime() <= now.getTime() ? "expired" : "withdrawn";
}
