import type { OrgId } from "@/shared/domain/ids";
import { queryScopes } from "@/shared/presentation/query";
import { queryOptions, type QueryKey } from "@tanstack/react-query";
import type { ListInvites } from "../application/list-invites";
import type { PreviewInvite } from "../application/preview-invite";
import { INVITE_STATUSES } from "../domain/invite";
import type { InviteListQuery } from "../domain/invite-list-query";

export const inviteKeys = {
    all: (orgId: OrgId) => [...queryScopes.org(orgId), "invites"] as const,
    /** Every page of every status, so a change to one invite reaches all of them. */
    lists: (orgId: OrgId) => [...inviteKeys.all(orgId), "list"] as const,
    list: (orgId: OrgId, query: InviteListQuery) => [...inviteKeys.lists(orgId), query] as const,
    /** Outside every organization: the invitee isn't a member of it yet. */
    preview: (token: string) => ["invite-preview", token] as const,
};

export const inviteQueries = {
    list: (listInvites: ListInvites, orgId: OrgId, query: InviteListQuery) =>
        queryOptions({
            queryKey: inviteKeys.list(orgId, query),
            queryFn: () => listInvites(orgId, query),
        }),
    preview: (previewInvite: PreviewInvite, token: string) =>
        queryOptions({
            queryKey: inviteKeys.preview(token),
            queryFn: () => previewInvite(token),
        }),
};

function isInviteListQuery(value: unknown): value is InviteListQuery {
    if (typeof value !== "object" || value === null || !("status" in value)) return false;
    const { status } = value;
    return status === null || (typeof status === "string" && (INVITE_STATUSES as readonly string[]).includes(status));
}

/** The query a cached list was asked with, read back from its key. */
export function listQueryOf(key: QueryKey): InviteListQuery | undefined {
    const last = key.at(-1);
    return isInviteListQuery(last) ? last : undefined;
}
