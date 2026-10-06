import { createLoader, createParser, parseAsInteger } from "nuqs/server";
import { DEFAULT_TEAM_LIST_PARAMS, formatTeamSort, parseTeamSort, sameTeamSort } from "../domain/team-list-query";

const teamSort = createParser({
    parse: (value) => parseTeamSort(value) ?? null,
    serialize: formatTeamSort,
    eq: sameTeamSort,
});

/** Shared by the teams page's server prefetch and the grid on the client, so both ask for the same thing. */
export const teamSearchParams = {
    sort: teamSort.withDefault(DEFAULT_TEAM_LIST_PARAMS.sort),
    page: parseAsInteger.withDefault(DEFAULT_TEAM_LIST_PARAMS.page),
};

export const loadTeamSearchParams = createLoader(teamSearchParams);

/** The team page's people, one page at a time. */
export const teamMemberSearchParams = {
    page: parseAsInteger.withDefault(1),
};

export const loadTeamMemberSearchParams = createLoader(teamMemberSearchParams);
