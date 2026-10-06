import type { OrgId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import type { Invite } from "../domain/invite";
import type { InviteListQuery } from "../domain/invite-list-query";
import type { InviteRepository } from "./ports";

export type ListInvites = (orgId: OrgId, query: InviteListQuery) => Promise<Page<Invite>>;

export function makeListInvites(invites: InviteRepository): ListInvites {
    return (orgId, query) => invites.list(orgId, query);
}
