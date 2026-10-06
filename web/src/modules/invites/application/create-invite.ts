import type { OrgId } from "@/shared/domain/ids";
import type { NewInvite } from "../domain/create-invite";
import type { Invite } from "../domain/invite";
import type { InviteRepository } from "./ports";

export type CreateInvite = (orgId: OrgId, invite: NewInvite) => Promise<Invite>;

export function makeCreateInvite(invites: InviteRepository): CreateInvite {
    return (orgId, invite) => invites.create(orgId, invite);
}
