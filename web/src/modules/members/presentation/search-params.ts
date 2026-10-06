import { ROLES } from "@/shared/domain/role";
import { createLoader, createParser, parseAsInteger, parseAsString, parseAsStringLiteral } from "nuqs/server";
import { MEMBER_STATUSES } from "../domain/member";
import {
    DEFAULT_MEMBER_LIST_PARAMS,
    formatMemberSort,
    parseMemberSort,
    sameMemberSort,
} from "../domain/member-list-query";

/** How long typing has to pause before a search reaches the URL and the backend. */
export const SEARCH_DEBOUNCE_MS = 300;

export const MEMBERS_PAGE_TABS = ["members", "invites"] as const;

export type MembersPageTab = (typeof MEMBERS_PAGE_TABS)[number];

export const membersTabParam = parseAsStringLiteral(MEMBERS_PAGE_TABS).withDefault("members");

const memberSort = createParser({
    parse: (value) => parseMemberSort(value) ?? null,
    serialize: formatMemberSort,
    eq: sameMemberSort,
});

/** Shared by the page's server prefetch and the list on the client, so both ask for the same thing. */
export const memberSearchParams = {
    q: parseAsString.withDefault(DEFAULT_MEMBER_LIST_PARAMS.q),
    role: parseAsStringLiteral(ROLES),
    status: parseAsStringLiteral(MEMBER_STATUSES).withDefault(DEFAULT_MEMBER_LIST_PARAMS.status),
    sort: memberSort.withDefault(DEFAULT_MEMBER_LIST_PARAMS.sort),
    page: parseAsInteger.withDefault(DEFAULT_MEMBER_LIST_PARAMS.page),
    size: parseAsInteger.withDefault(DEFAULT_MEMBER_LIST_PARAMS.size),
};

export const loadMemberSearchParams = createLoader({ ...memberSearchParams, tab: membersTabParam });
