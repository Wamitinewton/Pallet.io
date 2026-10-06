import { createLoader, parseAsInteger, parseAsStringLiteral } from "nuqs/server";
import { DEFAULT_INVITE_LIST_PARAMS, INVITE_STATUS_FILTERS } from "../domain/invite-list-query";

/**
 * Named apart from the members list's `status` and `page`, which share the address bar. Shared by the
 * page's server prefetch and the tab on the client, so both ask for the same thing.
 */
export const inviteSearchParams = {
    inviteStatus: parseAsStringLiteral(INVITE_STATUS_FILTERS).withDefault(DEFAULT_INVITE_LIST_PARAMS.inviteStatus),
    invitePage: parseAsInteger.withDefault(DEFAULT_INVITE_LIST_PARAMS.invitePage),
};

export const loadInviteSearchParams = createLoader(inviteSearchParams);
