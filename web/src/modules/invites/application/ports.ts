import type { InviteId, OrgId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import type { NewInvite } from "../domain/create-invite";
import type { Invite } from "../domain/invite";
import type { InviteListQuery } from "../domain/invite-list-query";
import type { InvitePreview } from "../domain/invite-preview";
import type { InviteToken } from "../domain/invite-token";

export interface InviteRepository {
    list(orgId: OrgId, query: InviteListQuery): Promise<Page<Invite>>;
    /** Emails the invitee a single-use link. */
    create(orgId: OrgId, invite: NewInvite): Promise<Invite>;
    /** Emails a fresh link with a new expiry. */
    resend(orgId: OrgId, inviteId: InviteId): Promise<Invite>;
    revoke(orgId: OrgId, inviteId: InviteId): Promise<void>;
}

/** The invitee's side: every call is authorized by the emailed token rather than a membership. */
export interface InviteAcceptanceRepository {
    preview(token: InviteToken): Promise<InvitePreview>;
    /** Creates the invited address's account, already verified, and joins it to the organization. */
    acceptWithNewAccount(token: InviteToken, password: string): Promise<void>;
    /** Joins the signed-in account, which must own the invited address. */
    acceptAsSignedIn(token: InviteToken): Promise<void>;
}
